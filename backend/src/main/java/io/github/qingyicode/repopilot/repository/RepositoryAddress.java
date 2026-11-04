package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import java.net.URI;

public record RepositoryAddress(String owner,String name) {
    public static RepositoryAddress parse(String input) {
        if(input==null || input.isBlank() || input.length()>2048) throw DomainException.invalid("INVALID_REPOSITORY_ADDRESS");
        String text=input.trim();
        try {
            if(text.startsWith("git@github.com:")) text=text.substring("git@github.com:".length());
            else if(text.contains("://")) {
                var uri=URI.create(text);
                if(!"https".equalsIgnoreCase(uri.getScheme()) || !("github.com".equalsIgnoreCase(uri.getHost())||"www.github.com".equalsIgnoreCase(uri.getHost()))
                        || uri.getUserInfo()!=null || (uri.getPort()!=-1&&uri.getPort()!=443)) throw new IllegalArgumentException();
                String[] path=uri.getPath().split("/",-1);
                if(path.length<3) throw new IllegalArgumentException();
                text=path[1]+"/"+path[2];
            }
            text=text.replaceFirst("/+$","").replaceFirst("(?i)\\.git$","");
            String[] parts=text.split("/",-1);if(parts.length!=2) throw new IllegalArgumentException();
            String[] normalized=RepositoryPolicy.fullName(parts[0],parts[1]).split("/");
            return new RepositoryAddress(normalized[0],normalized[1]);
        } catch(IllegalArgumentException invalid) {throw DomainException.invalid("INVALID_REPOSITORY_ADDRESS");}
    }
    public String fullName() {return owner+"/"+name;}
}
