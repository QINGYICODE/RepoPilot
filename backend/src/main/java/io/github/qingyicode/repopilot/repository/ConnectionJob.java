package io.github.qingyicode.repopilot.repository;

public record ConnectionJob(String id,String owner,String name,boolean authorizeAccess,boolean autoImport,Long repoId,
                            String syncJobId,String status,int attempts,String errorCode,String retryAt,String updatedAt) {}
