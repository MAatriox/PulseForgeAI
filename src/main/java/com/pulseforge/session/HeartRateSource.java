package com.pulseforge.session;

/** A live heart-rate stream (F07 input). Real wearables would implement this over BLE/companion APIs. */
public interface HeartRateSource {
    /** Current heart rate in bpm. */
    int currentBpm();

    /** Called when a set is completed so the source can model the post-set HR spike. */
    void onSetCompleted(double rpe);

    String describe();
}
