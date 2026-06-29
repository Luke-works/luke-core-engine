package com.luke.engine.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * DOC-4 verification: the candidate-group decision logic, with the Camunda "fact" lookups stubbed
 * (no running engine). Covers task assignee / candidate user / candidate group, and case-level
 * participant rules (starter, group-on-process, uploader-before-start).
 */
class CamundaTaskAccessResolverTest {

    /** Overrides the protected Camunda fact methods with in-memory data. */
    static class Stub extends CamundaTaskAccessResolver {
        Map<String, Set<String>> groupsByUser = Map.of(
                "alice", Set.of("legal"),
                "bob", Set.of("data-entry"),
                "carol", Set.of());
        Map<String, TaskIdentity> taskById = Map.of(
                "task-legal", new TaskIdentity(null, Set.of(), Set.of("legal")),
                "task-assigned", new TaskIdentity("bob", Set.of(), Set.of()),
                "task-canduser", new TaskIdentity(null, Set.of("carol"), Set.of()));
        Map<String, String> starterByPid = Map.of("P1", "alice");
        Map<String, Set<String>> procGroupsByPid = Map.of("P1", Set.of("legal"));

        Stub() { super(null, null, null); }
        @Override protected Set<String> userGroups(String u) { return groupsByUser.getOrDefault(u, Set.of()); }
        @Override protected TaskIdentity taskIdentity(String t) {
            return taskById.getOrDefault(t, new TaskIdentity(null, Set.of(), Set.of()));
        }
        @Override protected String processStarter(String p) { return starterByPid.get(p); }
        @Override protected Set<String> processCandidateGroups(String p) { return procGroupsByPid.getOrDefault(p, Set.of()); }
    }

    private static Document doc(String taskId, String pid, String createdBy) {
        Document d = new Document();
        d.setTenantId("t1");
        d.setProcessRef("proc-A");
        d.setProcessInstanceId(pid);
        d.setTaskId(taskId);
        d.setKind(Document.KIND_GENERIC);
        d.setCapability("FORMS");
        d.setCreatedBy(createdBy);
        return d;
    }

    private final Stub r = new Stub();

    @Test
    void taskCandidateGroupMemberAllowedOthersDenied() {
        Document d = doc("task-legal", "P1", "someone");
        assertThat(r.canAccess("t1", "alice", d)).isTrue();   // alice ∈ legal
        assertThat(r.canAccess("t1", "bob", d)).isFalse();    // bob ∈ data-entry only
    }

    @Test
    void taskAssigneeAllowed() {
        Document d = doc("task-assigned", "P1", "someone");
        assertThat(r.canAccess("t1", "bob", d)).isTrue();     // bob is the assignee
        assertThat(r.canAccess("t1", "carol", d)).isFalse();
    }

    @Test
    void taskCandidateUserAllowed() {
        Document d = doc("task-canduser", "P1", "someone");
        assertThat(r.canAccess("t1", "carol", d)).isTrue();   // carol is a candidate user
        assertThat(r.canAccess("t1", "bob", d)).isFalse();
    }

    @Test
    void caseLevelBeforeProcessOnlyUploader() {
        Document d = doc(null, null, "carol");                // taskId null, no process yet
        assertThat(r.canAccess("t1", "carol", d)).isTrue();   // the uploader
        assertThat(r.canAccess("t1", "alice", d)).isFalse();  // nobody else, even with groups
    }

    @Test
    void caseLevelParticipants() {
        Document d = doc(null, "P1", "zoe");                  // started process, uploaded by zoe
        assertThat(r.canAccess("t1", "alice", d)).isTrue();   // starter of P1
        assertThat(r.canAccess("t1", "bob", d)).isFalse();    // data-entry, not on P1
        assertThat(r.canAccess("t1", "zoe", d)).isTrue();     // the uploader
        // a legal member who didn't start it is still a participant (group used on P1)
        assertThat(r.canAccess("t1", "alice", doc(null, "P1", "x"))).isTrue();
    }

    @Test
    void uploadGatingTaskVsCaseLevel() {
        // task-level upload honors task access
        assertThat(r.canUpload("t1", "alice", "proc-A", "P1", "task-legal")).isTrue();
        assertThat(r.canUpload("t1", "bob", "proc-A", "P1", "task-legal")).isFalse();
        // case-level upload allowed (capability gates it upstream)
        assertThat(r.canUpload("t1", "bob", "proc-A", null, null)).isTrue();
        // anonymous denied
        assertThat(r.canUpload("t1", "", "proc-A", null, null)).isFalse();
    }
}
