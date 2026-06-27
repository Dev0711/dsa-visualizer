package com.dsaviz.engine;

import com.dsaviz.model.StepSnapshot;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives a debuggee JVM via JDI's LAUNCHING connector (simpler than
 * attaching over a socket for Phase 1 — the LaunchingConnector starts the
 * child JVM FOR us, already suspended, and gives us a VirtualMachine handle
 * directly. No manual -agentlib:jdwp flags or socket address juggling needed).
 *
 * Flow:
 *   1. Get the "com.sun.jdi.CommandLineLaunch" connector
 *   2. Set its "main" argument to "Main" (suspended at launch)
 *   3. vm.resume() once we've set up a STEP request so we don't race past
 *      the very first line
 *   4. Pump the VM's event queue: on each StepEvent, snapshot locals,
 *      then request the next step
 *   5. On VMDeathEvent/VMDisconnectEvent, stop and read captured stdout
 *      for the __RESULT__ marker line
 *
 * SAFETY LIMITS baked in:
 *   - MAX_STEPS guards against accidental infinite loops in pasted code
 *     (e.g. an off-by-one bug causing a while(true)) from hanging the
 *     service forever.
 *   - A hard timeout on the whole session as a second safety net.
 */
public class JdiStepEngine {

    private static final int MAX_STEPS = 5000;
    private static final long SESSION_TIMEOUT_MS = 15_000;

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

    public TraceResult trace(Path classOutputDir) throws Exception {
        LaunchingConnector connector = findLaunchingConnector();

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("main").setValue("Main");
        // classpath for the launched child JVM must point at our compiled output dir
        arguments.get("options").setValue("-cp " + classOutputDir.toAbsolutePath());

        VirtualMachine vm = connector.launch(arguments);

        // Drain stdout/stderr of the debuggee in a background thread —
        // REQUIRED. If you don't actively read the child process's output
        // streams, its internal buffer fills up and the JVM hangs once it's
        // full. This bites a lot of people writing their first JDI program.
        StringBuilder stdoutCapture = new StringBuilder();
        Thread stdoutPump = startStreamPump(vm.process().getInputStream(), stdoutCapture);
        Thread stderrPump = startStreamPump(vm.process().getErrorStream(), new StringBuilder());
        stdoutPump.setDaemon(true);
        stderrPump.setDaemon(true);
        stdoutPump.start();
        stderrPump.start();

        List<StepSnapshot> steps = new ArrayList<>();
        boolean truncated = false;

        EventRequestManager erm = vm.eventRequestManager();
        EventQueue queue = vm.eventQueue();

        // IMPORTANT: we cannot just vm.resume() and hope to catch a thread
        // reference before the program finishes — for a fast method like
        // Two Sum the whole thing can run to completion in well under a
        // millisecond, and we'd miss everything. Instead we explicitly
        // request a ThreadStartEvent BEFORE resuming, so JDI itself
        // guarantees we get a synchronous notification the instant the
        // main thread starts, with the VM still suspended at that point —
        // only THEN do we install the real line-step request and resume
        // again to actually start tracing.
        var threadStartRequest = erm.createThreadStartRequest();
        threadStartRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        threadStartRequest.enable();

        vm.resume();

        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
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

                if (!stepRequestInstalled && event instanceof com.sun.jdi.event.ThreadStartEvent threadStartEvent) {
                    ThreadReference startedThread = threadStartEvent.thread();
                    if (startedThread.name().equals("main")) {
                        StepRequest stepRequest = erm.createStepRequest(
                                startedThread,
                                StepRequest.STEP_LINE,
                                StepRequest.STEP_INTO);
                        stepRequest.addClassExclusionFilter("java.*");
                        stepRequest.addClassExclusionFilter("javax.*");
                        stepRequest.addClassExclusionFilter("sun.*");
                        stepRequest.addClassExclusionFilter("jdk.*");
                        stepRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        stepRequest.enable();
                        stepRequestInstalled = true;
                        threadStartRequest.disable();
                    }
                }

                if (event instanceof StepEvent stepEvent) {
                    try {
                        StepSnapshot snapshot = captureSnapshot(stepEvent, steps.size());
                        if (snapshot != null) {
                            steps.add(snapshot);
                        }
                    } catch (Exception captureError) {
                        // A single bad frame (e.g. variable temporarily out
                        // of scope at this exact line) shouldn't kill the
                        // whole trace - skip this step's variable capture
                        // but keep going.
                    }

                    if (steps.size() >= MAX_STEPS) {
                        truncated = true;
                        break eventLoop;
                    }
                }
            }

            try {
                eventSet.resume();
            } catch (VMDisconnectedException disconnected) {
                break;
            }
        }

        try {
            vm.process().destroyForcibly();
        } catch (Exception ignored) {
        }

        return new TraceResult(steps, stdoutCapture.toString(), truncated);
    }

    private LaunchingConnector findLaunchingConnector() {
        for (Connector c : Bootstrap.virtualMachineManager().allConnectors()) {
            if (c.name().equals("com.sun.jdi.CommandLineLaunch")) {
                return (LaunchingConnector) c;
            }
        }
        throw new IllegalStateException(
                "No CommandLineLaunch JDI connector found. This usually means the " +
                "JVM running this Spring Boot service is missing the jdk.jdi module " +
                "or tools.jar (pre-JDK9) - see README for the required JVM flags.");
    }

    private ThreadReference findMainThread(VirtualMachine vm) {
        for (ThreadReference t : vm.allThreads()) {
            if (t.name().equals("main")) {
                return t;
            }
        }
        return null;
    }

    private StepSnapshot captureSnapshot(StepEvent event, int stepIndex) {
        try {
            ThreadReference thread = event.thread();
            StackFrame frame = thread.frame(0);
            Location location = frame.location();

            String declaringClass = location.declaringType().name();
            // Only trace into the user's Solution class methods, not Main's
            // own setup lines, so the visualizer stays focused on the
            // algorithm itself.
            if (!declaringClass.equals("Solution")) {
                return null;
            }

            StepSnapshot snapshot = new StepSnapshot(stepIndex, location.lineNumber(), location.method().name());

            List<LocalVariable> visibleVars = frame.visibleVariables();
            for (LocalVariable var : visibleVars) {
                Object javaValue = JdiValueConverter.toJavaValue(frame.getValue(var), thread);
                snapshot.getVariables().put(var.name(), javaValue);
            }

            return snapshot;
        } catch (com.sun.jdi.AbsentInformationException | com.sun.jdi.IncompatibleThreadStateException e) {
            // AbsentInformationException: frame has no debug info available
            // at this exact instant (rare, usually right at method entry/exit).
            // IncompatibleThreadStateException: thread wasn't actually suspended
            // when we tried to read its frame (shouldn't normally happen given
            // our SUSPEND_ALL policy, but the JDI API still declares it checked).
            // Either way: skip just this one snapshot, keep the trace going.
            return null;
        }
    }

    private Thread startStreamPump(InputStream stream, StringBuilder sink) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.append(line).append("\n");
                }
            } catch (IOException ignored) {
                // stream closed because the process died - expected at end of trace
            }
        });
    }
}
