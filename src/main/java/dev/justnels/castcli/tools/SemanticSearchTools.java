package dev.justnels.castcli.tools;

import dev.justnels.castcli.index.WorkspaceEmbeddingIndex;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/** Exposes {@link WorkspaceEmbeddingIndex#search} as a model tool, so an agent can find code by meaning
 * instead of only by the literal/glob matching {@link WorkspaceTools} offers. Requires an index built
 * ahead of time via {@code llm-harness index}. */
public final class SemanticSearchTools {
    private final WorkspaceEmbeddingIndex index;

    public SemanticSearchTools(WorkspaceEmbeddingIndex index) {
        this.index = index;
    }

    @Tool("Semantically searches a pre-built embedding index of the workspace for code/text relevant to a "
            + "natural-language query (e.g. 'where do we validate matchmaking tickets'), returning the most "
            + "similar chunks with file path, line range, and relevance score. Complements searchWorkspace's "
            + "literal-text matching. Requires the index to have been built with 'llm-harness index' first.")
    public List<String> semanticSearchWorkspace(
            @P("natural language description of the code/concept to find") String query,
            @P("maximum number of results, from 1 to 50") int maxResults) {
        int limit = checkedLimit(maxResults);
        return index.searchOrAutoRebuild(query, limit).stream()
                .map(hit -> String.format("%s:%d-%d (score %.3f)%n%s",
                        hit.sourcePath(), hit.startLine(), hit.endLine(), hit.score(), hit.text()))
                .toList();
    }

    private static int checkedLimit(int maxResults) {
        if (maxResults < 1 || maxResults > 50) {
            throw new IllegalArgumentException("maxResults must be between 1 and 50");
        }
        return maxResults;
    }
}

