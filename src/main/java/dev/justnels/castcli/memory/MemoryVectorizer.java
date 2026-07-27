package dev.justnels.castcli.memory;

@FunctionalInterface
public interface MemoryVectorizer {
    float[] vectorize(String text);
}
