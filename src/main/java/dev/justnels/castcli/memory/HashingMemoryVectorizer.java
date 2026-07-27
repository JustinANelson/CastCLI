package dev.justnels.castcli.memory;

import java.util.Locale;

/** Dependency-free feature hashing used for deterministic local vector recall. */
public final class HashingMemoryVectorizer implements MemoryVectorizer {
    private static final int DIMENSIONS = 256;

    @Override
    public float[] vectorize(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) return vector;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (token.isBlank()) continue;
            int hash = token.hashCode();
            int index = (hash & Integer.MAX_VALUE) % DIMENSIONS;
            vector[index] += (hash & 1) == 0 ? 1f : -1f;
        }
        double norm = 0;
        for (float value : vector) norm += value * value;
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) vector[i] *= scale;
        }
        return vector;
    }
}
