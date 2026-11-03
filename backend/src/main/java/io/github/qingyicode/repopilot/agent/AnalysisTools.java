package io.github.qingyicode.repopilot.agent;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.dependency.DependencyData;
import io.github.qingyicode.repopilot.dependency.DependencyService;
import io.github.qingyicode.repopilot.incident.IncidentData;
import io.github.qingyicode.repopilot.incident.IncidentService;
import io.github.qingyicode.repopilot.indexing.IndexStore;
import io.github.qingyicode.repopilot.repository.RepositoryData.Evidence;
import java.util.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** One instance per run; repository and versions are never model arguments. */
public final class AnalysisTools {
    final AnalysisData.Bound bound;
    private final IndexStore indices;
    private final DependencyService dependencies;
    private final IncidentService incidents;
    final Map<String,AnalysisData.Citation> issued=new LinkedHashMap<>();
    final Set<String> authoritative=new LinkedHashSet<>();
    DependencyData.Result dependency;
    IncidentData.Result incident;

    public AnalysisTools(AnalysisData.Bound bound,IndexStore indices,DependencyService dependencies,IncidentService incidents) {
        this.bound=bound;this.indices=indices;this.dependencies=dependencies;this.incidents=incidents;
    }
    @Tool(description="Compare the task's authorized Maven base/head versions and return affected services, paths and evidence. No arguments. Use for DEPENDENCY_IMPACT.")
    public AnalysisData.ToolResult compareDependencyChanges() {
        var r=bound.request();
        if(r.task()!=AnalysisData.Task.DEPENDENCY_IMPACT || r.baseSnapshotId()==null) throw DomainException.invalid("WRONG_TASK_TOOL");
        var candidate=dependencies.compare(r.repoId(),r.baseSnapshotId(),r.snapshotId(),r.module(),8);
        var refs=new LinkedHashMap<String,Long>();
        for(var change:candidate.changes()) {
            if(change.before()!=null) change.before().sources().forEach(s->refs.put(s.evidenceId(),r.baseSnapshotId()));
            if(change.after()!=null) change.after().sources().forEach(s->refs.put(s.evidenceId(),r.snapshotId()));
        }
        for(var impact:candidate.impacts()) impact.evidenceIds().forEach(id->refs.put(id,impact.view().equals("BASE")?r.baseSnapshotId():r.snapshotId()));
        if(refs.size()>100) throw DomainException.conflict("EVIDENCE_BUDGET_LIMIT");
        refs.forEach((id,snapshot)->issue(snapshot,id,false));
        authoritative.addAll(refs.keySet());dependency=candidate;
        var data=new LinkedHashMap<String,Object>();
        data.put("affectedServices",dependency.affectedServices());
        data.put("changes",dependency.changes().stream().limit(12).map(c->Map.of("module",c.module(),"coordinate",c.coordinate(),"kind",c.kind())).toList());
        data.put("impacts",dependency.impacts().stream().limit(12).toList());
        data.put("truncated",dependency.truncated()||dependency.impacts().size()>12||dependency.changes().size()>12);
        return payload(dependency.changes().isEmpty()?"EMPTY":"OK",data,authoritative,dependency.warnings());
    }
    @Tool(description="Find historical repair candidates for the task's alert text and optional service/deployed SHA. No arguments. Returns actual changed-line evidence; candidates are never confirmed incident links.")
    public AnalysisData.ToolResult searchFixCandidates() {
        var r=bound.request();
        if(r.task()!=AnalysisData.Task.INCIDENT_FIX) throw DomainException.invalid("WRONG_TASK_TOOL");
        var candidate=incidents.search(r.repoId(),r.snapshotId(),r.module(),r.question(),r.deployedSha());
        candidate.candidates().forEach(c->c.evidence().forEach(e->issue(r.snapshotId(),e.id(),false)));
        candidate.candidates().forEach(c->c.evidence().forEach(e->authoritative.add(e.id())));incident=candidate;
        var data=incident.candidates().stream().map(c->Map.of("commitSha",c.commitSha(),"subject",c.subject(),"classification",c.classification(),
                "applicability",c.applicability(),"matchedTerms",c.matchedTerms(),"evidenceIds",c.evidence().stream().map(Evidence::id).toList())).toList();
        return payload(data.isEmpty()?"EMPTY":"OK",data,authoritative,incident.warnings());
    }
    @Tool(description="Search FILE, COMMIT or DIFF evidence within the task's fixed head snapshot. Use a short literal exception, config key or symbol, not a whole question. Read-only.")
    public AnalysisData.ToolResult searchRepository(@ToolParam(description="1-200 character literal search term") String query,
                                                    @ToolParam(description="FILE, COMMIT or DIFF") String type) {
        var r=bound.request();
        var page=indices.search(r.repoId(),r.snapshotId(),query,type,null,null,null,r.module(),5,null);
        page.items().forEach(e->issue(r.snapshotId(),e.id(),false));
        var data=page.items().stream().map(e->Map.of("id",e.id(),"type",e.type(),"sha",e.sourceSha(),"path",e.path(),"excerpt",clip(e.excerpt(),500))).toList();
        return payload(data.isEmpty()?"EMPTY":"OK",data,page.items().stream().map(Evidence::id).toList(),
                page.truncated()?List.of("SEARCH_PAGE_TRUNCATED"):List.of());
    }
    @Tool(description="Read an evidence ID previously returned in this run. Unissued IDs or references to another repository/version are forbidden.")
    public AnalysisData.ToolResult readEvidence(@ToolParam(description="Previously issued 64-character evidence ID") String evidenceId) {
        var citation=issued.get(evidenceId);
        if(citation==null) throw new DomainException(org.springframework.http.HttpStatus.FORBIDDEN,"UNISSUED_EVIDENCE");
        var e=indices.evidence(bound.request().repoId(),citation.snapshotId(),evidenceId);
        return payload("OK",Map.of("id",e.id(),"sha",e.sourceSha(),"path",e.path(),"excerpt",clip(e.excerpt(),1800),"truncated",e.truncated()||e.excerpt().length()>1800),List.of(e.id()),List.of());
    }
    private void issue(long snapshot,String id,boolean business) {
        if(id==null || id.isBlank()) return;
        if(!issued.containsKey(id)) {
            var e=indices.evidence(bound.request().repoId(),snapshot,id);
            issued.put(id,new AnalysisData.Citation(e.id(),snapshot,e.type(),e.sourceSha(),e.path(),e.startLine(),e.endLine(),clip(e.excerpt(),2400),e.sourceUrl()));
        }
        if(business) authoritative.add(id);
    }
    void verifyBusinessEvidence() {
        if(authoritative.isEmpty()) throw DomainException.conflict("NO_BUSINESS_EVIDENCE");
        for(String id:authoritative) {
            var citation=issued.get(id);
            if(citation==null) throw DomainException.conflict("UNVERIFIED_CITATION");
            var current=indices.evidence(bound.request().repoId(),citation.snapshotId(),id);
            if(!current.id().equals(id) || current.repoId()!=bound.request().repoId() || !current.sourceSha().equals(citation.sourceSha())
                    || !current.path().equals(citation.path())) throw DomainException.conflict("EVIDENCE_SCOPE_MISMATCH");
        }
        if(dependency!=null && dependency.impacts().stream().anyMatch(i->i.evidenceIds().isEmpty()||!authoritative.containsAll(i.evidenceIds())))
            throw DomainException.conflict("UNSUPPORTED_IMPACT_PATH");
        if(incident!=null && incident.candidates().stream().anyMatch(c->!c.classification().equals("RELATED_CANDIDATE")
                || c.evidence().stream().noneMatch(e->e.type().equals("DIFF")))) throw DomainException.conflict("UNSUPPORTED_FIX_CLAIM");
    }
    private static AnalysisData.ToolResult payload(String status,Object data,Collection<String> ids,List<String> warnings) {
        return new AnalysisData.ToolResult(status,data,ids.stream().limit(40).toList(),warnings,null);
    }
    private static String clip(String text,int max) {return text.length()>max?text.substring(0,max)+"\n[TRUNCATED]":text;}
}
