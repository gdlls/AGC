package io.papermc.paper.agc.event;

/**
 * AGC — Zero-overhead Cancellable Event Marker Interface.
 */
public interface AgcCancellable {

    boolean isCancelled();

    void setCancelled(boolean cancel);
}
