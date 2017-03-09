package com.cloudbees.jenkins.plugins.bitbucket.api;

import java.util.Date;

/**
 * Created by wysa on 17/3/9.
 */
public interface BitbucketTag {
    String getName();
    String getBranch();
    String getHash();
    Date getDate();
}
