package com.cloudbees.jenkins.plugins.bitbucket;

import hudson.Extension;
import jenkins.branch.BranchBuildStrategy;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.mixin.TagSCMHead;

/**
 * Created by wysa on 17/3/9.
 */
@Extension
public class BitbucketBranchBuildStrategy extends BranchBuildStrategy {

    @Override
    public boolean isAutomaticBuild(SCMSource source, SCMHead head) {
        return true;
    }
}
