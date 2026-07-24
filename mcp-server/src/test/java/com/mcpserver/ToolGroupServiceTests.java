package com.mcpserver;

import com.mcpserver.connectors.AuthMode;
import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionRepository;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.connectors.ConnectionType;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolRepository;
import com.mcpserver.tools.ToolGroup;
import com.mcpserver.tools.ToolGroupRepository;
import com.mcpserver.tools.ToolGroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ToolGroupServiceTests {

    @Autowired
    private ToolGroupService toolGroupService;
    @Autowired
    private ToolGroupRepository toolGroupRepository;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private ConnectionRepository connectionRepository;
    @Autowired
    private ApiToolRepository apiToolRepository;

    private final List<String> createdConnectionIds = new ArrayList<>();
    private final List<String> createdGroupIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // tests share the real SQLite DB — remove everything this run created
        for (String id : createdGroupIds) {
            toolGroupRepository.deleteMembersForGroup(id);
            toolGroupRepository.delete(id);
        }
        for (String id : createdConnectionIds) {
            apiToolRepository.deleteByConnectionId(id);
            connectionRepository.deleteById(id);
        }
    }

    private ToolGroup newGroup(String name) {
        ToolGroup group = toolGroupService.create(name, null);
        createdGroupIds.add(group.id());
        return group;
    }

    private Connection newConnection(String name) {
        Connection c = Connection.create(ConnectionType.API_COLLECTION, name + "-" + UUID.randomUUID(),
                "http://example.com", AuthMode.NONE, null, null, List.of());
        connectionRepository.save(c);
        createdConnectionIds.add(c.id());
        return c;
    }

    /** GET tools start enabled; anything else starts pending (mirrors the import rules). */
    private ApiTool newTool(Connection c, String appSlug, String requestSlug, String method) {
        boolean read = method.equals("GET");
        ApiTool t = new ApiTool(UUID.randomUUID().toString(), c.id(), appSlug,
                appSlug + "_" + requestSlug, requestSlug, requestSlug, "", "general", method,
                "/" + requestSlug.replace('_', '/'), "{\"type\":\"object\"}", "{}", "{}", null, null,
                read, !read, false, Instant.now(), Instant.now());
        apiToolRepository.save(t);
        return t;
    }

    private static ToolGroup.ToolGroupMember appMember(Connection c) {
        return ToolGroup.ToolGroupMember.of(null, ToolGroup.MemberType.APP, c.id());
    }

    private static ToolGroup.ToolGroupMember toolMember(ApiTool t) {
        return ToolGroup.ToolGroupMember.of(null, ToolGroup.MemberType.TOOL, t.id());
    }

    @Test
    void createSlugifiesAndDedupesSlugs() {
        ToolGroup first = newGroup("Dev Tools");
        ToolGroup second = newGroup("Dev Tools");
        ToolGroup third = newGroup("Dev Tools!!");

        assertThat(first.slug()).isEqualTo("dev_tools");
        assertThat(second.slug()).isEqualTo("dev_tools_2");
        assertThat(third.slug()).isEqualTo("dev_tools_3");
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> toolGroupService.create("   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renameKeepsSlugStable() {
        ToolGroup group = newGroup("Dev");

        ToolGroup renamed = toolGroupService.update(group.id(), "Renamed Dev", "a description");

        assertThat(renamed.slug()).isEqualTo("dev");
        assertThat(renamed.name()).isEqualTo("Renamed Dev");
        assertThat(renamed.description()).isEqualTo("a description");
        assertThat(toolGroupService.findBySlug("dev")).isPresent();
    }

    @Test
    void setMembersBulkReplacesAndValidates() {
        Connection conn = newConnection("Alpha");
        ApiTool tool = newTool(conn, "alpha", "get_pets", "GET");
        ToolGroup group = newGroup("Dev");

        toolGroupService.setMembers(group.id(), List.of(appMember(conn), toolMember(tool)));
        assertThat(toolGroupRepository.findMembers(group.id())).hasSize(2);

        // bulk replace — the APP membership is gone, not merged
        toolGroupService.setMembers(group.id(), List.of(toolMember(tool)));
        assertThat(toolGroupRepository.findMembers(group.id()))
                .extracting(ToolGroup.ToolGroupMember::memberType)
                .containsExactly(ToolGroup.MemberType.TOOL);

        assertThatThrownBy(() -> toolGroupService.setMembers(group.id(),
                List.of(ToolGroup.ToolGroupMember.of(null, ToolGroup.MemberType.APP, "missing-conn"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> toolGroupService.setMembers(group.id(),
                List.of(ToolGroup.ToolGroupMember.of(null, ToolGroup.MemberType.TOOL, "missing-tool"))))
                .isInstanceOf(IllegalArgumentException.class);
        // failed validation leaves the existing membership untouched
        assertThat(toolGroupRepository.findMembers(group.id())).hasSize(1);
    }

    @Test
    void toolsInGroupExpandsAppsAndDirectToolsDeduped() {
        Connection alpha = newConnection("Alpha");
        ApiTool a1 = newTool(alpha, "alpha", "get_pets", "GET");
        ApiTool a2 = newTool(alpha, "alpha", "create_pet", "POST");
        Connection beta = newConnection("Beta");
        ApiTool b1 = newTool(beta, "beta", "get_orders", "GET");
        ToolGroup group = newGroup("Dev");

        // a1 is reachable both via the app and as a direct member — counted once
        toolGroupService.setMembers(group.id(),
                List.of(appMember(alpha), toolMember(b1), toolMember(a1)));

        assertThat(toolGroupService.toolsInGroup(group.id()))
                .extracting(ApiTool::id)
                .containsExactlyInAnyOrder(a1.id(), a2.id(), b1.id());
    }

    @Test
    void setGroupEnabledFlipsEveryToolIncludingPending() {
        Connection alpha = newConnection("Alpha");
        ApiTool readTool = newTool(alpha, "alpha", "get_pets", "GET");
        ApiTool writeTool = newTool(alpha, "alpha", "create_pet", "POST"); // pending
        ToolGroup group = newGroup("Dev");
        toolGroupService.setMembers(group.id(), List.of(appMember(alpha)));

        int enabledCount = toolGroupService.setGroupEnabled(group.id(), true);
        assertThat(enabledCount).isEqualTo(2);
        assertThat(apiToolRepository.findById(readTool.id()).orElseThrow().enabled()).isTrue();
        ApiTool writeAfter = apiToolRepository.findById(writeTool.id()).orElseThrow();
        assertThat(writeAfter.enabled()).isTrue();
        assertThat(writeAfter.pending()).isFalse();

        int disabledCount = toolGroupService.setGroupEnabled(group.id(), false);
        assertThat(disabledCount).isEqualTo(2);
        assertThat(apiToolRepository.findById(readTool.id()).orElseThrow().enabled()).isFalse();
        assertThat(apiToolRepository.findById(writeTool.id()).orElseThrow().enabled()).isFalse();
    }

    @Test
    void resolveInGroupMirrorsKeywordSemantics() {
        Connection alpha = newConnection("Alpha");
        ApiTool getPets = newTool(alpha, "alpha", "get_pets", "GET");
        ApiTool getPetById = newTool(alpha, "alpha", "get_pet_by_id", "GET");
        newTool(alpha, "alpha", "create_pet", "POST");
        ToolGroup group = newGroup("Dev");
        toolGroupService.setMembers(group.id(), List.of(appMember(alpha)));

        // empty keyword → everything in the group
        assertThat(toolGroupService.resolveInGroup("dev", ""))
                .extracting(ApiTool::name)
                .containsExactlyInAnyOrder("alpha_get_pets", "alpha_get_pet_by_id", "alpha_create_pet");
        // exact full-name match
        assertThat(toolGroupService.resolveInGroup("dev", "alpha_get_pets"))
                .extracting(ApiTool::id).containsExactly(getPets.id());
        // exact request-slug match
        assertThat(toolGroupService.resolveInGroup("dev", "get_pets"))
                .extracting(ApiTool::id).containsExactly(getPets.id());
        // ambiguous prefix → all candidates for the suggestion list
        assertThat(toolGroupService.resolveInGroup("dev", "get"))
                .extracting(ApiTool::id)
                .containsExactlyInAnyOrder(getPets.id(), getPetById.id());
        // no match → empty
        assertThat(toolGroupService.resolveInGroup("dev", "zzz")).isEmpty();
    }

    @Test
    void deletingAConnectionRemovesItsGroupMemberships() {
        Connection alpha = newConnection("Alpha");
        ApiTool tool = newTool(alpha, "alpha", "get_pets", "GET");
        ToolGroup group = newGroup("Dev");
        toolGroupService.setMembers(group.id(), List.of(appMember(alpha), toolMember(tool)));
        assertThat(toolGroupRepository.findMembers(group.id())).hasSize(2);

        connectionService.delete(alpha.id());

        assertThat(toolGroupRepository.findMembers(group.id())).isEmpty();
        assertThat(apiToolRepository.findByConnectionId(alpha.id())).isEmpty();
    }
}
