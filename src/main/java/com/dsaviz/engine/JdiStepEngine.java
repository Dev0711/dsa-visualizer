package com.dsaviz.engine;

import com.dsaviz.model.StepSnapshot;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives a debuggee JVM via JDI, using ProcessBuilder + SocketAttach.
 *
 * <p>Why not CommandLineLaunch?
 * CommandLineLaunch is JDI's built-in "start the child and connect" connector.
 * In practice it is unreliable in Linux/Alpine/Docker containers because:
 *   - It uses an internal mechanism to pick an ephemeral port and pass it to
 *     the child JVM.  On dual-stack Linux systems (where "localhost" resolves
 *     to both ::1 and 127.0.0.1), the parent and child can end up on different
 *     IP families, causing: IOException: handshake failed - unrecognized message
 *     from target VM.
 *   - JDK_JAVA_OPTIONS / JAVA_TOOL_OPTIONS env vars cause the child to print
 *     "Picked up JDK_JAVA_OPTIONS: ..." before the JDWP handshake string, which
 *     corrupts the transport stream in some JDI implementations.
 *
 * <p>What we do instead (ProcessBuilder + SocketAttach):
 *   1. Locate the java binary from java.home (same JDK that's running Spring Boot).
 *   2. Launch the child with ProcessBuilder, binding JDWP explicitly to
 *      127.0.0.1:0 (IPv4, random free port).  Strip noisy env vars.
 *   3. Read the child's stderr until it announces the chosen port.
 *   4. Attach via com.sun.jdi.SocketAttach to 127.0.0.1:<port> — always IPv4,
 *      never subject to dual-stack resolution.
 *   5. From this point the event-loop logic is identical to the old approach.
 *
 * <p>Safety limits:
 *   - MAX_STEPS  — aborts runaway infinite loops.
 *   - SESSION_TIMEOUT_MS — hard wall-clock timeout on the whole session.
 */
public class JdiStepEngine {

    private static final int MAX_STEPS = 5000;
    private static final long SESSION_TIMEOUT_MS = 15_000;

    /** Pattern that the JVM prints to stderr when JDWP is ready. */
    private static final Pattern JDWP_PORT_PATTERN =
            Pattern.compile("Listening for transport dt_socket at address: (\\d+)");

    public static class TraceResult {
        public final List<StepSnapshot> steps;
        public final String stdout;
        public final boolean truncated;

        public TraceResult(List<StepSnapshot> steps, String stdout, boolean truncated) {
            this.steps = steps;
            this.stdout = stdout;
            this.truncated = truncated;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public TraceResult trace(Path classOutputDir) throws Exception {

        // ------------------------------------------------------------------
        // Step 1: Launch the child JVM with ProcessBuilder.
        //
        // We use java.home to find the exact same JDK binary that's running
        // the Spring Boot app — avoids PATH lookup surprises in Docker.
        //
        // Key JDWP options:
        //   transport=dt_socket  — TCP socket (cross-platform, no shared memory)
        //   server=y             — child listens, parent connects
        //   suspend=y            — child pauses until debugger attaches
        //   address=127.0.0.1:0 — bind to IPv4 loopback, OS picks free port
        //
        // We remove JDK_JAVA_OPTIONS / JAVA_TOOL_OPTIONS from the child's
        // environment so that "Picked up JDK_JAVA_OPTIONS: ..." is never
        // printed to stderr before the JDWP port announcement line.
        // ------------------------------------------------------------------
        String javaHome = System.getProperty("java.home");
        String javaBin  = javaHome + File.separator + "bin" + File.separator + "java";

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-Djava.net.preferIPv4Stack=true",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
                "-cp", classOutputDir.toAbsolutePath().toString(),
                "Main"
        );
        pb.environment().remove("JDK_JAVA_OPTIONS");
        pb.environment().remove("JAVA_TOOL_OPTIONS");

        Process process = pb.start();

        // ------------------------------------------------------------------
        // Step 2: Read the JDWP port from child's stderr.
        //
        // The JVM prints (to stderr, before the main class runs):
        //   "Listening for transport dt_socket at address: <PORT>"
        //
        // We parse this in a background thread that continues draining stderr
        // afterwards — stopping mid-read would stall the child when its stderr
        // pipe buffer fills up.
        // ------------------------------------------------------------------
        StringBuilder stderrCapture = new StringBuilder();
        int port = readJdwpPort(process.getErrorStream(), stderrCapture);
        if (port < 0) {
            process.destroyForcibly();
            throw new IOException(
                    "JDWP agent did not announce a port within 10 seconds. " +
                    "This usually means the child JVM crashed at startup. " +
                    "stderr:\n" + stderrCapture);
        }

        // ------------------------------------------------------------------
        // Step 3: Attach to the child via SocketAttach.
        //
        // hostname=127.0.0.1 — explicit IPv4, no dual-stack ambiguity
        // timeout=5000        — 5-second connect timeout (child is already up)
        // ------------------------------------------------------------------
        AttachingConnector socketAttach = findSocketAttachConnector();
        Map<String, Connector.Argument> connArgs = socketAttach.defaultArguments();
        connArgs.get("hostname").setValue("127.0.0.1");
        connArgs.get("port").setValue(String.valueOf(port));
        connArgs.get("timeout").setValue("5000");

        VirtualMachine vm;
        try {
            vm = socketAttach.attach(connArgs);
        } catch (Exception e) {
            process.destroyForcibly();
            throw new IOException(
                    "Failed to attach to child JVM on 127.0.0.1:" + port +
                    " — " + e.getMessage(), e);
        }

        // Drain child stdout (required — buffer fill-up stalls the child process).
        StringBuilder stdoutCapture = new StringBuilder();
        Thread stdoutPump = startStreamPump(process.getInputStream(), stdoutCapture);
        stdoutPump.setDaemon(true);
        stdoutPump.start();

        // ------------------------------------------------------------------
        // Step 4: Event loop — identical to the original CommandLineLaunch
        //         version from this point onwards.
        // ------------------------------------------------------------------
        List<StepSnapshot> steps = new ArrayList<>();
        boolean truncated = false;

        EventRequestManager erm   = vm.eventRequestManager();
        EventQueue          queue = vm.eventQueue();

        // Register a ThreadStartEvent BEFORE vm.resume() so we catch the
        // very first moment the main thread exists and can install a step
        // request before any user code runs.
        var threadStartRequest = erm.createThreadStartRequest();
        threadStartRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        threadStartRequest.enable();

        vm.resume();

        long    deadline             = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        boolean stepRequestInstalled = false;

        eventLoop:
        while (System.currentTimeMillis() < deadline) {
            EventSet eventSet;
            try {
                eventSet = queue.remove(500);
            } catch (InterruptedException e) {
                break;
            }
            if (eventSet == null) {
                continue;
            }

            for (Event event : eventSet) {
                if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                    break eventLoop;
                }

                if (!stepRequestInstalled
                        && event instanceof com.sun.jdi.event.ThreadStartEvent tse) {
                    ThreadReference startedThread = tse.thread();
                    if (startedThread.name().equals("main")) {
                        createStepRequest(erm, startedThread);
                        stepRequestInstalled = true;
                        threadStartRequest.disable();
                    }
                }

                if (event instanceof StepEvent stepEvent) {
                    StepRequest req = (StepRequest) stepEvent.request();
                    req.disable(); // prevent re-entry during captureSnapshot

                    try {
                        StepSnapshot snapshot = captureSnapshot(stepEvent, steps.size());
                        if (snapshot != null) {
                            steps.add(snapshot);
                        }
                    } catch (Exception ignored) {
                        // skip this one snapshot, keep tracing
                    }

                    if (steps.size() >= MAX_STEPS) {
                        truncated = true;
                        break eventLoop;
                    }

                    req.enable();
                }
            }

            try {
                eventSet.resume();
            } catch (VMDisconnectedException e) {
                break;
            }
        }

        try {
            process.destroyForcibly();
        } catch (Exception ignored) {
        }

        return new TraceResult(steps, stdoutCapture.toString(), truncated);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the child JVM's stderr stream in a background thread until the
     * JDWP port announcement line is found, then keeps draining to avoid
     * blocking the child.
     *
     * @param stderrStream  the child process's stderr
     * @param stderrCapture accumulates stderr text for diagnostic messages
     * @return the JDWP port number, or -1 if not found within 10 seconds
     */
    private int readJdwpPort(InputStream stderrStream, StringBuilder stderrCapture)
            throws InterruptedException {

        AtomicInteger foundPort = new AtomicInteger(-1);
        CountDownLatch portLatch = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(stderrStream))) {
                String line;
                boolean portFound = false;
                while ((line = br.readLine()) != null) {
                    stderrCapture.append(line).append("\n");
                    if (!portFound) {
                        Matcher m = JDWP_PORT_PATTERN.matcher(line);
                        if (m.find()) {
                            foundPort.set(Integer.parseInt(m.group(1)));
                            portLatch.countDown();
                            portFound = true;
                            // Don't break — keep draining to prevent pipe stall
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                portLatch.countDown(); // unblock caller if stream closes without match
            }
        });
        reader.setDaemon(true);
        reader.start();

        portLatch.await(10, TimeUnit.SECONDS);
        return foundPort.get();
    }

    private AttachingConnector findSocketAttachConnector() {
        for (Connector c : Bootstrap.virtualMachineManager().allConnectors()) {
            if (c.name().equals("com.sun.jdi.SocketAttach")) {
                return (AttachingConnector) c;
            }
        }
        throw new IllegalStateException(
                "No SocketAttach JDI connector found. The JVM running this " +
                "Spring Boot service must have the jdk.jdi module available — " +
                "see README for the required --add-modules jdk.jdi JVM flag.");
    }

    private void createStepRequest(EventRequestManager erm, ThreadReference thread) {
        StepRequest stepRequest = erm.createStepRequest(
                thread,
                StepRequest.STEP_LINE,
                StepRequest.STEP_INTO);
        stepRequest.addClassExclusionFilter("java.*");
        stepRequest.addClassExclusionFilter("javax.*");
        stepRequest.addClassExclusionFilter("sun.*");
        stepRequest.addClassExclusionFilter("jdk.*");
        stepRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        stepRequest.enable();
    }

    private StepSnapshot captureSnapshot(StepEvent event, int stepIndex) {
        try {
            ThreadReference thread = event.thread();
            StackFrame      frame  = thread.frame(0);
            Location        loc    = frame.location();

            String declaringClass = loc.declaringType().name();
            // Only capture steps inside the user's Solution class — not Main's
            // own setup code — so the visualizer stays focused on the algorithm.
            if (!declaringClass.equals("Solution")) {
                return null;
            }

            StepSnapshot snapshot =
                    new StepSnapshot(stepIndex, loc.lineNumber(), loc.method().name());

            List<LocalVariable> visibleVars = frame.visibleVariables();
            for (LocalVariable var : visibleVars) {
                Object javaValue = JdiValueConverter.toJavaValue(frame.getValue(var), thread);
                snapshot.getVariables().put(var.name(), javaValue);
            }

            return snapshot;

        } catch (com.sun.jdi.AbsentInformationException
                 | com.sun.jdi.IncompatibleThreadStateException e) {
            // Rare: no debug info at this exact moment, or thread not suspended.
            // Skip this snapshot; the trace continues.
            return null;
        }
    }

    private Thread startStreamPump(InputStream stream, StringBuilder sink) {
        return new Thread(() -> {
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.append(line).append("\n");
                }
            } catch (IOException ignored) {
                // stream closed when the child process exits — expected
            }
        });
    }
}
