package com.mcpserver.connectors;

import com.mcpserver.rag.retrieval.TextSignals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a bounded set of strong page-title / issue-key search matches into searchable content.
 * The in-flight guard prevents concurrent identical searches from downloading the same item twice.
 */
@Service
public class ConnectorContentResolver {

    private static final Logger log = LoggerFactory.getLogger(ConnectorContentResolver.class);

    private final SourceCatalogRepository catalogRepository;
    private final ConnectionRepository connectionRepository;
    private final Map<ConnectionType, SourceConnector> connectorsByType;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final int titleMatchLimit;

    public ConnectorContentResolver(SourceCatalogRepository catalogRepository,
                                    ConnectionRepository connectionRepository,
                                    List<SourceConnector> connectors,
                                    @Value("${connectors.lazy-title-match-limit:3}") int titleMatchLimit) {
        this.catalogRepository = catalogRepository;
        this.connectionRepository = connectionRepository;
        this.connectorsByType = connectors.stream()
                .collect(Collectors.toMap(SourceConnector::type, Function.identity()));
        this.titleMatchLimit = Math.max(1, Math.min(titleMatchLimit, 10));
    }

    /** Returns how many remote bodies were fetched for this search. */
    public int hydrateTitleMatches(String query) {
        if (query == null || query.isBlank()) return 0;
        List<CatalogResource> matches = catalogRepository
                .findTitleCandidates(query, Math.max(20, titleMatchLimit * 5)).stream()
                .filter(resource -> resource.contentState() != CatalogContentState.INDEXED)
                .filter(resource -> isStrongMatch(query, resource))
                .sorted(Comparator
                        .comparing((CatalogResource resource) -> !exactMatch(query, resource))
                        .thenComparing(Comparator.comparingInt(
                                (CatalogResource resource) -> resource.title().length()).reversed())
                        .thenComparing(CatalogResource::catalogedAt, Comparator.reverseOrder())
                        .thenComparing(CatalogResource::id))
                .toList();

        int hydrated = 0;
        for (CatalogResource resource : matches) {
            if (hydrated >= titleMatchLimit) break;
            if (!inFlight.add(resource.id())) continue;
            try {
                Connection connection = connectionRepository.findById(resource.connectionId()).orElse(null);
                if (connection == null || connection.status() != ConnectionStatus.CONNECTED) continue;
                SourceConnector connector = connectorsByType.get(connection.type());
                if (connector == null) continue;
                connector.hydrate(connection, resource);
                catalogRepository.markIndexed(resource.id());
                hydrated++;
            } catch (Exception e) {
                // Search still runs against already indexed local content. A later query retries this
                // metadata-only row instead of poisoning the search request or losing the pointer.
                log.warn("Lazy hydration failed for {}:{} ({}): {}",
                        resource.sourceSystem(), resource.externalId(), resource.title(), e.getMessage());
            } finally {
                inFlight.remove(resource.id());
            }
        }
        return hydrated;
    }

    public boolean hasCatalogEntries() {
        return catalogRepository.countResources() > 0;
    }

    private static boolean isStrongMatch(String query, CatalogResource resource) {
        if (exactMatch(query, resource)) return true;
        String normalizedQuery = normalize(query);
        String normalizedTitle = normalize(resource.title());
        if (normalizedTitle.length() >= 3 && normalizedQuery.contains(normalizedTitle)) return true;

        Set<String> titleTerms = TextSignals.terms(resource.title());
        Set<String> queryTerms = TextSignals.terms(query);
        return !titleTerms.isEmpty() && queryTerms.containsAll(titleTerms);
    }

    private static boolean exactMatch(String query, CatalogResource resource) {
        String normalizedQuery = normalize(query);
        return normalizedQuery.equals(normalize(resource.title()))
                || normalizedQuery.equals(normalize(resource.externalId()));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
