package dev.justnels.castcli.index;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VectorMathTest {

    @Test
    void cosineSimilarityIdenticalVectors() {
        float[] v1 = new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
        float[] v2 = new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f};

        double score = VectorMath.cosineSimilarity(v1, v2);

        assertThat(score).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void cosineSimilarityOrthogonalVectors() {
        float[] v1 = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
        float[] v2 = new float[]{0.0f, 1.0f, 0.0f, 0.0f};

        double score = VectorMath.cosineSimilarity(v1, v2);

        assertThat(score).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void cosineSimilarityEdgeCases() {
        assertThat(VectorMath.cosineSimilarity(null, new float[]{1.0f})).isEqualTo(0.0);
        assertThat(VectorMath.cosineSimilarity(new float[]{1.0f}, new float[]{1.0f, 2.0f})).isEqualTo(0.0);
        assertThat(VectorMath.cosineSimilarity(new float[]{0.0f, 0.0f}, new float[]{0.0f, 0.0f})).isEqualTo(0.0);
    }

    @Test
    void dotProductCalculatesCorrectly() {
        float[] v1 = new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
        float[] v2 = new float[]{2.0f, 0.0f, 1.0f, 3.0f, 4.0f};

        double dot = VectorMath.dotProduct(v1, v2);

        assertThat(dot).isEqualTo(37.0);
    }
}
