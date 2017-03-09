package com.cloudbees.jenkins.plugins.bitbucket.api;

import java.util.Date;
import java.util.List;

/**
 * Created by wysa on 17/3/9.
 */
public interface BitbucketTag {
    String getName();
    String getBranch();
    List<String> getBranches();
    String getHash();
    Date getDate();
}
