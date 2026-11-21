package com.catarapatara.engine;

/** A recorded-engine player: renders the engine at an rpm and a throttle load (0..1). */
public interface PackEngine {
    void render(float[] out, int off, int len, float rpm, float load);
}
