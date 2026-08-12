package io.github.jtplatform.simulator.signal;

import java.util.concurrent.CompletionStage;
import org.yzh.protocol.t1078.T9101;
import org.yzh.protocol.t1078.T9102;

public interface SignalCommandHandler {
    CompletionStage<Integer> open(T9101 command);

    CompletionStage<Integer> control(T9102 command);

    void onSignalDisconnected();
}
