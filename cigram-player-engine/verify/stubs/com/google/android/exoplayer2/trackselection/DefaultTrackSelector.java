package com.google.android.exoplayer2.trackselection;
public class DefaultTrackSelector {
    public static class Parameters {
        public static final class Builder {
            public Builder setTrackTypeDisabled(int trackType, boolean disabled) { return this; }
        }
    }
    public Parameters.Builder buildUponParameters() { return new Parameters.Builder(); }
    public void setParameters(Parameters.Builder builder) { }
}
