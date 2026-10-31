package org.universaltranslator.core.net;

/**
 * A bound this transport decided on: the peer is not delivering, the network is fine.
 *
 * <p>A stall used to be reported as a bare {@link java.net.SocketTimeoutException}, which every
 * retry decision in the project reads as "the network was momentarily slow, try again" — the exact
 * opposite of what a peer that was given its whole budget and delivered less than a window's worth
 * has proven. This subtype keeps that classification (and every message string) so nothing changes
 * for an existing {@code instanceof SocketTimeoutException} check, and gives the retry decision one
 * value it can single out when it is ready to stop paying for the same peer again.
 */
public final class TranslationBudgetExceededException extends java.net.SocketTimeoutException {
    private static final long serialVersionUID = 1L;

    public TranslationBudgetExceededException(String message) {
        super(message);
    }
}
