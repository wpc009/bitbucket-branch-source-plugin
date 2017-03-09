package com.cloudbees.jenkins.plugins.bitbucket.client.tag;

import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketTag;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.Date;
import java.util.List;

/**
 * Created by wysa on 17/3/9.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketCloudTag implements BitbucketTag {

    private String name;
    private String branch;
    private List<String> branches;

    @JsonProperty("raw_node")
    private String hash;

    private Date date;

    @Override
    public List<String> getBranches() {
        return branches;
    }

    public void setBranches(List<String> branches) {
        this.branches = branches;
    }

    @Override
    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }


    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    @Override
    public Date getDate() {
        return date;
    }

    public void setDate(Date date) {
        this.date = date;
    }
}
