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
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives a debuggee JVM via JDI, using ProcessBuilder + SocketAttach.
 *
 * <h3>Why not CommandLineLaunch?</h3>
 * CommandLineLaunch is JDI's built-in "start the child and connect" connector.
 * It is unreliable inside Linux/Alpine/Docker containers because:
 * <ul>
 *   <li>On dual-stack Linux hosts, "localhost" can resolve to ::1 (IPv6) for the
 *       parent but 127.0.0.1 (IPv4) for the child's JDWP agent — causing
 *       "handshake failed - unrecognized message from target VM".</li>
 *   <li>JDK_JAVA_OPTIONS / JAVA_TOOL_OPTIONS env vars can inject extra output
 *       before the JDWP handshake text, corrupting the transport stream.</li>
 * </ul>
 *
 * <h3>What we do instead (ProcessBuilder + SocketAttach)</h3>
 * <ol>
 *   <li>Allocate a free port ourselves via ServerSocket(0) before starting
 *       the child — this gives us a known, non-conflicting port number.</li>
 *   <li>Start the child with ProcessBuilder, specifying JDWP explicitly:
 *       {@code server=y,suspend=y,address=127.0.0.1:<port>}.
 *       Binding to 127.0.0.1 (loopback) prevents external health-checkers
 *       (e.g. Render's HTTP health probe) from hitting the JDWP port.</li>
 *   <li>Poll for connection with SocketAttach to 127.0.0.1:<port> — always
 *       IPv4, never subject to dual-stack resolution or health-check races.</li>
 *   <li>The event-loop step-tracing logic is unchanged from the original.</li>
 * </ol>
 *
 * <h3>Why polling instead of parsing stderr?</h3>
 * On different JDK/platform combinations the "Listening for transport"
 * announcement line uses either bare-port format ({@code 12345}) or
 * host:port format ({@code 127.0.0.1:12345}).  Because we already know the
 * port (we allocated it ourselves), parsing stderr is unnecessary and fragile.
 * Polling with SocketAttach until the child's JDWP agent is ready is simpler
 * and works identically on all platforms.
 *
 * <h3>Safety limits</h3>
 * <ul>
 *   <li>MAX_STEPS — aborts runaway infinite loops in user code.</li>
 *   <li>SESSION_TIMEOUT_MS — hard wall-clock timeout on the whole session.</li>
 *   <li>ATTACH_TIMEOUT_MS — maximum time to wait for JDWP to start.</li>
 * </ul>
 */
public class JdiStepEngine {

    private static final int  MAX_STEPS          = 5000;
    private static final long SESSION_TIMEOUT_MS  = 15_000;
    private static final long ATTACH_TIMEOUT_MS   = 10_000;
    private static final long ATTACH_POLL_INTERVAL = 200;   // ms between attach attempts

    // -------------------------------------------------------------------------

    public static class TraceResult {
        public final List<StepSnapshot> steps;
        public final String stdout;
        public final boolean truncated;

        public TraceResult(List<StepSnapshot> steps, String stdout, boolean truncated) {
            this.steps     = steps;
            this.stdout    = stdout;
            this.truncated = truncated;
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public TraceResult trace(Path classOutputDir) throws Exception {

        // ------------------------------------------------------------------
        // Step 1: Allocate a free port BEFORE starting the child JVM.
        //
        // Using ServerSocket(0) lets the OS assign a truly free port.  We
        // close the socket immediately so the port is available for JDWP to
        // bind — there is a tiny race window, but in practice this is
        // negligible because no external process is scanning for free ports.
        //
        // Binding JDWP to 127.0.0.1:<port> (IPv4 loopback, specific port)
        // ensures:
        //   a) only connections originating on this host can reach it, and
        //   b) we always know the exact port to connect to without parsing
        //      stderr (whose format varies by JDK version and platform).
        // ------------------------------------------------------------------
        int jdwpPort = findFreePort();

        String javaHome = System.getProperty("java.home");
        String javaBin  = javaHome + File.separator + "bin" + File.separator + "java";

        // ------------------------------------------------------------------
        // Step 2: Launch the child JVM with ProcessBuilder.
        //
        // We strip JDK_JAVA_OPTIONS, JAVA_TOOL_OPTIONS, and _JAVA_OPTIONS
        // from the child's environment.  These env vars cause the JVM to
        // print "Picked up ..." noise to stderr which, in previous versions
        // of this code that relied on parsing stderr, corrupted our output.
        // We no longer parse stderr for the port, but stripping them still
        // prevents any unexpected side effects on the child's JVM options.
        // ------------------------------------------------------------------
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-Djava.net.preferIPv4Stack=true",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y"
                        + ",address=127.0.0.1:" + jdwpPort,
                "-cp", classOutputDir.toAbsolutePath().toString(),
                "Main"
        );
        pb.environment().remove("JDK_JAVA_OPTIONS");
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("_JAVA_OPTIONS");

        Process process = pb.start();

        // Drain stderr and stdout in background threads.
        // REQUIRED: if we don't actively read these streams the child process
        // will stall once its pipe buffers fill up.
        StringBuilder stderrCapture = new StringBuilder();
        StringBuilder stdoutCapture = new StringBuilder();
        Thread stderrPump = startStreamPump(process.getErrorStream(), stderrCapture);
        Thread stdoutPump = startStreamPump(process.getInputStream(), stdoutCapture);
        stderrPump.setDaemon(true);
        stdoutPump.setDaemon(true);
        stderrPump.start();
        stdoutPump.start();

        // ------------------------------------------------------------------
        // Step 3: Attach to the child JVM via SocketAttach (poll until ready).
        //
        // We poll in a loop rather than doing a single blocking attach()
        // because the JDWP agent inside the child JVM takes a moment to
        // initialize and start listening after the process starts.  The
        // timeout in connArgs is per-attempt; we keep trying until
        // ATTACH_TIMEOUT_MS has elapsed.
        // ------------------------------------------------------------------
        AttachingConnector socketAttach = findSocketAttachConnector();
        Map<String, Connector.Argument> connArgs = socketAttach.defaultArguments();
        connArgs.get("hostname").setValue("127.0.0.1");
        connArgs.get("port").setValue(String.valueOf(jdwpPort));
        connArgs.get("timeout").setValue("1000"); // 1-second per attempt

        VirtualMachine vm        = null;
        long           deadline  = System.currentTimeMillis() + ATTACH_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException(
                        "Child JVM exited before JDWP became ready.\n" +
                        "stderr:\n" + stderrCapture);
            }
            try {
                vm = socketAttach.attach(connArgs);
                break; // success
            } catch (IOException attachErr) {
                // JDWP not ready yet — wait a moment and retry
                Thread.sleep(ATTACH_POLL_INTERVAL);
            }
        }

        if (vm == null) {
            process.destroyForcibly();
            throw new IOException(
                    "Could not attach to child JVM at 127.0.0.1:" + jdwpPort +
                    " within " + (ATTACH_TIMEOUT_MS / 1000) + " seconds.\n" +
                    "stderr:\n" + stderrCapture);
        }

        // ------------------------------------------------------------------
        // Step 4: Event loop — step through the user's code line by line.
        // ------------------------------------------------------------------
        List<StepSnapshot> steps    = new ArrayList<>();
        boolean            truncated = false;

        EventRequestManager erm   = vm.eventRequestManager();
        EventQueue          queue = vm.eventQueue();

        // Register a ThreadStartEvent BEFORE vm.resume() so we catch the
        // instant the main thread starts and can install the step request
        // before any user code executes.
        var threadStartRequest = erm.createThreadStartRequest();
        threadStartRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        threadStartRequest.enable();

        vm.resume();

        long    sessionDeadline      = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        boolean stepRequestInstalled = false;

        eventLoop:
        while (System.currentTimeMillis() < sessionDeadline) {
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
                    req.disable(); // prevent re-entry during snapshot capture

                    try {
                        StepSnapshot snapshot = captureSnapshot(stepEvent, steps.size());
                        if (snapshot != null) {
                            steps.add(snapshot);
                        }
                    } catch (Exception ignored) {
                        // skip this snapshot; keep tracing
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

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Allocates a free ephemeral port on the loopback interface.
     * The socket is closed immediately so JDWP can bind to the same port.
     * There is a tiny race window between close() and JDWP bind(), but no
     * external process scans for free loopback ports in practice.
     */
    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
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
                "check that --add-modules jdk.jdi is on the command line.");
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
            // setup code — so the visualizer stays focused on the algorithm.
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
            // Rare: debug info not available at this exact moment, or thread
            // not actually suspended.  Skip this snapshot; the trace continues.
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
