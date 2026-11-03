package io.github.qingyicode.repopilot.incident;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import io.github.qingyicode.repopilot.indexing.IndexStore;
import io.github.qingyicode.repopilot.indexing.Redactor;
import io.github.qingyicode.repopilot.repository.GitMirror;
import io.github.qingyicode.repopilot.repository.RepositoryStore;
import io.github.qingyicode.repopilot.repository.RepositoryData.Evidence;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Bounded lexical retrieval: a similar patch is a candidate, never an incident linkage. */
@Service
public class IncidentService {
    private final RepositoryStore repositories;
    private final IndexStore indices;
    private final GitMirror mirror;
    private static final Pattern TERMS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_.$/-]{3,119}");
    private static final Pattern FIX = Pattern.compile("(?i)\\b(fix(?:ed|es)?|bug|repair|resolve[ds]?|correct(?:ed)?|prevent|handle[ds]?)\\b|修复|解决");
    private static final Set<String> NOISE = Set.of("error","warn","info","exception","java.lang","service","http","https","null","true","false","this","that","with","from","failed");

    public IncidentService(RepositoryStore repositories, IndexStore indices, GitMirror mirror) {
        this.repositories=repositories; this.indices=indices; this.mirror=mirror;
    }

    public IncidentData.Result search(long repoId, long snapshotId, String service, String message, String deployedSha) {
        var snapshot=repositories.snapshot(repoId,snapshotId);
        if (message==null || message.isBlank() || message.length()>8000) throw DomainException.invalid("INVALID_INCIDENT_MESSAGE");
        if (service!=null && !service.isBlank()) {
            if(!service.equals(".")) RepositoryPolicy.path(service);
            if (indices.graph(repoId,snapshotId).modules().stream().noneMatch(m->m.path().equals(service)))
                throw DomainException.invalid("UNKNOWN_SERVICE_MODULE");
        }
        var record=repositories.repository(repoId);
        if (deployedSha!=null && !deployedSha.isBlank()) {
            deployedSha=RepositoryPolicy.sha(deployedSha);
            if (!mirror.isAncestor(record,deployedSha,snapshot.commitSha())) throw DomainException.invalid("DEPLOYED_SHA_OUTSIDE_KNOWLEDGE");
        } else deployedSha=null;
        var terms=signatures(message);
        var pool=new LinkedHashMap<String,Evidence>();
        var warnings=new ArrayList<String>(List.of("SIMILARITY_IS_NOT_CONFIRMED_LINK", "REVERT_AND_EQUIVALENT_PATCH_NOT_VERIFIED", "LEXICAL_RETRIEVAL_ONLY"));
        // Each search has a bounded page. Explicitly report an incomplete candidate pool.
        for (String term:terms) for (String type:List.of("COMMIT","DIFF")) {
            var page=indices.search(repoId,snapshotId,term,type,null,null,null,
                    type.equals("DIFF") && service!=null && !service.isBlank()?service:null,20,null);
            page.items().forEach(e->pool.put(e.id(),e));
            if(page.truncated()) warnings.add("CANDIDATE_POOL_TRUNCATED");
        }
        var grouped=new LinkedHashMap<String,List<Evidence>>();
        pool.values().forEach(e->grouped.computeIfAbsent(e.sourceSha(),ignored->new ArrayList<>()).add(e));
        var candidates=new ArrayList<IncidentData.Candidate>();
        if(grouped.size()>40) warnings.add("CANDIDATE_POOL_TRUNCATED");
        var ranked=grouped.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String,List<Evidence>> e)->
                (int)terms.stream().filter(t->e.getValue().stream().anyMatch(v->contains(v.excerpt(),t))).count()).reversed()).limit(40).toList();
        for(var entry:ranked) {
            String sha=entry.getKey();
            // Exact SHA retrieval obtains commit text and actual hunks from the same authorized snapshot.
            var commitPage=indices.search(repoId,snapshotId,sha,"COMMIT",null,null,sha,null,1,null);
            if(commitPage.items().isEmpty()) continue;
            var commit=commitPage.items().getFirst();
            if(!FIX.matcher(commit.excerpt()).find() || commit.excerpt().toLowerCase(Locale.ROOT).startsWith("revert")) continue;
            var diffPage=indices.search(repoId,snapshotId,sha,"DIFF",null,null,sha,
                    service!=null && !service.isBlank()?service:null,20,null);
            if(diffPage.truncated()) warnings.add("CANDIDATE_DIFF_TRUNCATED");
            var relevant=diffPage.items().stream().filter(e->e.parentSha()!=null && !Boolean.TRUE.equals(e.locator().get("metadataOnly")))
                    .filter(e->terms.stream().anyMatch(t->contains(changedLines(e.excerpt()),t))).limit(3).toList();
            if(relevant.isEmpty()) continue;
            var matched=terms.stream().filter(t->relevant.stream().anyMatch(e->contains(changedLines(e.excerpt()),t))).toList();
            var evidence=new ArrayList<Evidence>(); evidence.add(commit); evidence.addAll(relevant);
            String applicability="DEPLOYED_VERSION_UNKNOWN";
            if(deployedSha!=null) applicability=mirror.isAncestor(record,sha,deployedSha)?"COMMIT_PRESENT_IN_DEPLOYED_ANCESTRY":"COMMIT_NOT_IN_DEPLOYED_ANCESTRY";
            candidates.add(new IncidentData.Candidate(sha,commit.excerpt().lines().findFirst().orElse(""),"RELATED_CANDIDATE",
                    applicability,matched.size()*10+(service!=null && !service.isBlank()?5:0),matched,List.copyOf(evidence)));
        }
        candidates.sort(Comparator.comparingInt(IncidentData.Candidate::score).reversed().thenComparing(IncidentData.Candidate::commitSha));
        if(candidates.size()>3) warnings.add("CANDIDATE_LIST_TRUNCATED");
        return new IncidentData.Result(candidates.isEmpty()?"REFUSED":"PARTIAL",candidates.isEmpty()?"NO_EVIDENCE":"CANDIDATE_ONLY",
                snapshot.commitSha(),deployedSha,terms,List.copyOf(candidates.subList(0,Math.min(3,candidates.size()))),
                warnings.stream().distinct().toList(),snapshot.coverage());
    }

    public static List<String> signatures(String message) {
        String safe=Redactor.clean(message).replaceAll("\\b[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}\\b", " ");
        var matcher=TERMS.matcher(safe); var terms=new LinkedHashSet<String>();
        while(matcher.find()) {
            String value=matcher.group();
            if(!NOISE.contains(value.toLowerCase(Locale.ROOT)) && !value.contains("REDACTED")) terms.add(value);
        }
        return terms.stream().sorted(Comparator.comparingInt((String t)->t.endsWith("Exception")||t.contains("_")||t.contains(".")?0:1))
                .limit(6).toList();
    }
    private static boolean contains(String text,String term) {return text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));}
    private static String changedLines(String patch) {
        return patch.lines().filter(line->(line.startsWith("+")&&!line.startsWith("+++"))||(line.startsWith("-")&&!line.startsWith("---")))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
