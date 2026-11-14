package com.algofuture.bots.liquiditysweep.application.port.out;

/** Where live signals are announced (Telegram), for a trader who executes them by hand. */
public interface AlertPort {

    /** Whether alerts can actually be delivered (e.g. a bot token and chat are configured). */
    boolean configured();

    void send(String text);
}
