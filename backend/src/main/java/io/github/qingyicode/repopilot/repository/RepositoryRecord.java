package io.github.qingyicode.repopilot.repository;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("repository")
public class RepositoryRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String owner;
    private String name;
    private String defaultRef;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDefaultRef() { return defaultRef; }
    public void setDefaultRef(String defaultRef) { this.defaultRef = defaultRef; }
}
