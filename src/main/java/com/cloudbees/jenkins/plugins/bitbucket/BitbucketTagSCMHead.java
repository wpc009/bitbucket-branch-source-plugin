package com.cloudbees.jenkins.plugins.bitbucket;

import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.mixin.TagSCMHead;

/**
 * Created by wysa on 17/3/8.
 */
public class BitbucketTagSCMHead extends SCMHead implements TagSCMHead{

    private long timestamp;
    private String hash;

    public BitbucketTagSCMHead(@NonNull String name, long timestamp,String hash) {
        super(name);
        this.timestamp = timestamp;
        this.hash = hash;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }
}
