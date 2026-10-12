package io.apitomy.axiom.app.composition;

/** A save was based on a content revision that is no longer current (HTTP 409). */
public class StaleRevisionException extends RuntimeException {
    private final String currentRevision;

    /**
     * @param currentRevision the item's current content revision
     */
    public StaleRevisionException(String currentRevision) {
        super("The item changed since it was loaded; current revision is " + currentRevision);
        this.currentRevision = currentRevision;
    }

    /**
     * @return the item's current content revision
     */
    public String currentRevision() {
        return currentRevision;
    }
}
