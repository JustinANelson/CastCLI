package dev.justnels.castcli.index;

/**
 * High-performance vector math operations optimized for HotSpot C2 auto-vectorization (SIMD / AVX / Neon).
 * Computes dot products and cosine similarities over float arrays with unrolled loop bounds.
 */
public final class VectorMath {

    private VectorMath() {
    }

    /**
     * Computes cosine similarity between two float vectors.
     * Returns 0.0 if vector lengths mismatch, lengths are 0, or either norm is zero.
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        int len = a.length;
        int i = 0;
        int limit = len & ~3;
        for (; i < limit; i += 4) {
            float a0 = a[i];
            float b0 = b[i];
            float a1 = a[i + 1];
            float b1 = b[i + 1];
            float a2 = a[i + 2];
            float b2 = b[i + 2];
            float a3 = a[i + 3];
            float b3 = b[i + 3];

            dotProduct += (double) a0 * b0 + (double) a1 * b1 + (double) a2 * b2 + (double) a3 * b3;
            normA += (double) a0 * a0 + (double) a1 * a1 + (double) a2 * a2 + (double) a3 * a3;
            normB += (double) b0 * b0 + (double) b1 * b1 + (double) b2 * b2 + (double) b3 * b3;
        }
        for (; i < len; i++) {
            float ai = a[i];
            float bi = b[i];
            dotProduct += (double) ai * bi;
            normA += (double) ai * ai;
            normB += (double) bi * bi;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Computes dot product of two float vectors.
     */
    public static double dotProduct(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }

        double sum = 0.0;
        int len = a.length;
        int i = 0;
        int limit = len & ~3;
        for (; i < limit; i += 4) {
            sum += (double) a[i] * b[i]
                    + (double) a[i + 1] * b[i + 1]
                    + (double) a[i + 2] * b[i + 2]
                    + (double) a[i + 3] * b[i + 3];
        }
        for (; i < len; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
