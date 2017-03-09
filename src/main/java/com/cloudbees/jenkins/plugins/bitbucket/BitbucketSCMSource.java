/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.plugins.bitbucket;

import com.cloudbees.jenkins.plugins.bitbucket.api.*;
import com.cloudbees.jenkins.plugins.bitbucket.client.branch.BitbucketCloudBranch;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Tuple;
import hudson.Extension;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Actionable;
import hudson.model.TaskListener;
import hudson.plugins.git.*;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.impl.BuildChooserSetting;
import hudson.plugins.git.util.BuildChooser;
import hudson.plugins.git.util.DefaultBuildChooser;
import hudson.plugins.mercurial.MercurialSCM;
import hudson.plugins.mercurial.MercurialSCM.RevisionType;
import hudson.scm.SCM;
import hudson.util.ExceptionCatchingThreadFactory;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.AbstractGitSCMSource.SpecificRevisionBuildChooser;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadCategory;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceEvent;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.metadata.ContributorMetadataAction;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import jenkins.scm.api.metadata.PrimaryInstanceMetadataAction;
import jenkins.scm.api.mixin.TagSCMHead;
import jenkins.scm.impl.ChangeRequestSCMHeadCategory;
import jenkins.scm.impl.TagSCMHeadCategory;
import jenkins.scm.impl.UncategorizedSCMHeadCategory;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.eclipse.jgit.lib.Constants;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * SCM source implementation for Bitbucket.
 * <p>
 * It provides a way to discover/retrieve branches and pull requests through the Bitbuclet REST API
 * which is much faster than the plain Git SCM source implementation.
 */
public class BitbucketSCMSource extends SCMSource {

    /**
     * Credentials used to access the Bitbucket REST API.
     */
    private String credentialsId;

    /**
     * Credentials used to clone the repository/repositories.
     */
    private String checkoutCredentialsId;

    /**
     * Repository owner.
     * Used to build the repository URL.
     */
    private final String repoOwner;

    /**
     * Repository name.
     * Used to build the repository URL.
     */
    private final String repository;

    /**
     * Ant match expression that indicates what branches to include in the retrieve process.
     */
    private String includes = "*";

    /**
     * Ant match expression that indicates what branches to exclude in the retrieve process.
     */
    private String excludes = "";

    /**
     * If true, a webhook will be auto-registered in the repository managed by this source.
     */
    private boolean autoRegisterHook = false;

    /**
     * Bitbucket Server URL.
     * An specific HTTP client is used if this field is not null.
     */
    private String bitbucketServerUrl;

    /**
     * Port used by Bitbucket Server for SSH clone.
     * -1 by default (for Bitbucket Cloud).
     */
    private int sshPort = -1;

    /**
     * Repository type.
     */
    private BitbucketRepositoryType repositoryType;

    /**
     * The cache of pull request titles for each open PR.
     */
    @CheckForNull
    private transient /*effectively final*/ Map<String, String> pullRequestTitleCache;
    /**
     * The cache of pull request contributors for each open PR.
     */
    @CheckForNull
    private transient /*effectively final*/ Map<String, ContributorMetadataAction> pullRequestContributorCache;

    private static final Logger LOGGER = Logger.getLogger(BitbucketSCMSource.class.getName());

    @DataBoundConstructor
    public BitbucketSCMSource(String id, String repoOwner, String repository) {
        super(id);
        this.repoOwner = repoOwner;
        this.repository = repository;
    }

    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmpty(credentialsId);
    }

    @CheckForNull
    public String getCheckoutCredentialsId() {
        return checkoutCredentialsId;
    }

    @DataBoundSetter
    public void setCheckoutCredentialsId(String checkoutCredentialsId) {
        this.checkoutCredentialsId = checkoutCredentialsId;
    }

    public String getIncludes() {
        return includes;
    }

    @DataBoundSetter
    public void setIncludes(@NonNull String includes) {
        Pattern.compile(getPattern(includes));
        this.includes = includes;
    }

    public String getExcludes() {
        return excludes;
    }

    @DataBoundSetter
    public void setExcludes(@NonNull String excludes) {
        Pattern.compile(getPattern(excludes));
        this.excludes = excludes;
    }

    public String getRepoOwner() {
        return repoOwner;
    }

    public String getRepository() {
        return repository;
    }

    @DataBoundSetter
    public void setAutoRegisterHook(boolean autoRegisterHook) {
        this.autoRegisterHook = autoRegisterHook;
    }

    public boolean isAutoRegisterHook() {
        return autoRegisterHook;
    }

    public int getSshPort() {
        return sshPort;
    }

    @DataBoundSetter
    public void setSshPort(int sshPort) {
        this.sshPort = sshPort;
    }

    @DataBoundSetter
    public void setBitbucketServerUrl(String url) {
        this.bitbucketServerUrl = Util.fixEmpty(url);
        if (this.bitbucketServerUrl != null) {
            // Remove a possible trailing slash
            this.bitbucketServerUrl = this.bitbucketServerUrl.replaceAll("/$", "");
        }
    }

    @CheckForNull
    public String getBitbucketServerUrl() {
        return bitbucketServerUrl;
    }

    private String bitbucketUrl() {
        return StringUtils.defaultIfBlank(bitbucketServerUrl, "https://bitbucket.org");
    }

    public String getRemote(@NonNull String repoOwner, @NonNull String repository, BitbucketRepositoryType repositoryType) {
        assert repositoryType != null;
        BitbucketRepositoryProtocol protocol;
        Integer protocolPortOverride = null;
        if (StringUtils.isBlank(checkoutCredentialsId)) {
            protocol = BitbucketRepositoryProtocol.HTTP;
        } else if (getCheckoutCredentials() instanceof SSHUserPrivateKey) {
            protocol = BitbucketRepositoryProtocol.SSH;
            if (sshPort > 0) {
                protocolPortOverride = sshPort;
            }
        } else {
            protocol = BitbucketRepositoryProtocol.HTTP;
        }
        return buildBitbucketClient().getRepositoryUri(repositoryType, protocol, protocolPortOverride, repoOwner, repository);
    }

    public BitbucketRepositoryType getRepositoryType() throws IOException, InterruptedException {
        if (repositoryType == null) {
            repositoryType = BitbucketRepositoryType.fromString(buildBitbucketClient().getRepository().getScm());
        }
        return repositoryType;
    }

    public BitbucketApi buildBitbucketClient() {
        return BitbucketApiFactory.newInstance(bitbucketServerUrl, getScanCredentials(), repoOwner, repository);
    }

    public BitbucketApi buildBitbucketClient(PullRequestSCMHead head) {
        return BitbucketApiFactory.newInstance(bitbucketServerUrl, getScanCredentials(), head.getRepoOwner(), head.getRepository());
    }

    @Override
    public void afterSave() {
        try {
            getRepositoryType();
        } catch (InterruptedException | IOException e) {
            LOGGER.log(Level.FINE,
                    "Could not determine repository type of " + getRepoOwner() + "/" + getRepository() + " on "
                            + StringUtils.defaultIfBlank(getBitbucketServerUrl(), "bitbucket.org") + " for "
                            + getOwner(), e);
        }
    }

    @Override
    protected void retrieve(@CheckForNull SCMSourceCriteria criteria, @NonNull SCMHeadObserver observer,
                            @CheckForNull SCMHeadEvent<?> event, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        if (event != null) {
            observer = event.filter(this, observer);
        }
        StandardUsernamePasswordCredentials scanCredentials = getScanCredentials();
        if (scanCredentials == null) {
            listener.getLogger().format("Connecting to %s with no credentials, anonymous access%n", bitbucketUrl());
        } else {
            listener.getLogger().format("Connecting to %s using %s%n", bitbucketUrl(), CredentialsNameProvider.name(scanCredentials));
        }
        // this has the side-effect of ensuring that repository type is always populated.
        listener.getLogger().format("Repository type: %s%n", WordUtils.capitalizeFully(getRepositoryType().name()));

        if (event != null) {
            Object payload = event.getPayload();
            if (payload instanceof BitbucketPushEvent) {
                // Search branches
                retrieveBranches(criteria, observer, listener, (BitbucketPushEvent) event.getPayload());
            } else if (payload instanceof BitbucketPullRequestEvent) {
                // Search pull requests
                retrievePullRequests(criteria, observer, listener);
            }
        } else {
            retrieveReleaseTags(criteria,observer,listener);
            retrieveBranches(criteria, observer, listener);
            retrievePullRequests(criteria, observer, listener);
        }
    }

    private void retrieveReleaseTags(SCMSourceCriteria criteria, SCMHeadObserver observer, final TaskListener listener)
        throws IOException, InterruptedException{
        String fullName = repoOwner + "/" + repository;
        listener.getLogger().format("Looking up %s for tags",fullName);
        final BitbucketApi bitbucket = buildBitbucketClient();
        final String defaultBranch = bitbucket.getDefaultBranch();
        if (bitbucket.isPrivate()){
            Set<SCMHead> includes = observer.getIncludes();
            List<? extends BitbucketTag> tags = bitbucket.getTags();
            for(BitbucketTag tag:tags) {
                if (tag.getBranches() != null && tag.getBranches().contains(defaultBranch) || defaultBranch.equals(tag.getBranch()))
                {
                    SCMHead head = new BitbucketTagSCMHead(tag.getName(),tag.getDate() != null ? tag.getDate().getTime():System.currentTimeMillis(),tag.getHash());
                    if(includes != null && !includes.contains(head)){
                        continue;
                    }
                    listener.getLogger().println("Checking tag " + tag.getName() + " from " + fullName);
                    observe(criteria, observer, listener, repoOwner, repository, defaultBranch,
                            tag.getHash(),
                            head
                    );
                    if (!observer.isObserving()) {
                        break;
                    }

                }
            }


        }

    }

    private void retrievePullRequests(SCMSourceCriteria criteria, SCMHeadObserver observer, final TaskListener listener)
            throws IOException, InterruptedException {
        String fullName = repoOwner + "/" + repository;
        listener.getLogger().println("Looking up " + fullName + " for pull requests");

        final BitbucketApi bitbucket = buildBitbucketClient();
        if (bitbucket.isPrivate()) {
            List<? extends BitbucketPullRequest> pulls = bitbucket.getPullRequests();
            Set<String> livePRs = new HashSet<>();
            Set<SCMHead> includes = observer.getIncludes();
            for (final BitbucketPullRequest pull : pulls) {
                checkInterrupt();
                PullRequestSCMHead head = new PullRequestSCMHead(pull.getSource().getRepository().getOwnerName(),
                        pull.getSource().getRepository().getRepositoryName(), repositoryType,
                        pull.getSource().getBranch().getName(), pull);
                if (includes != null && !includes.contains(head)) {
                    continue;
                }


                listener.getLogger().println(
                        "Checking PR from " + pull.getSource().getRepository().getFullName() + " and branch "
                                + pull.getSource().getBranch().getName());

                // Resolve full hash. See https://bitbucket.org/site/master/issues/11415/pull-request-api-should-return-full-commit

                String hash;
                try {
                    hash = bitbucket.resolveSourceFullHash(pull);
                } catch (BitbucketRequestException e) {
                    if (e.getHttpCode() == 403) {
                        listener.getLogger().println(
                                "Do not have permission to view PR from " + pull.getSource().getRepository().getFullName() + " and branch "
                                        + pull.getSource().getBranch().getName());
                        // the credentials do not have permission, so we should not observe the PR ever
                        // the PR is dead to us, so this is the one case where we can squash the exception.
                        continue;
                    } else {
                        // this is some other unexpected error, we need to abort observing, so throw.
                        throw e;
                    }
                }
                getPullRequestTitleCache().put(pull.getId(), StringUtils.defaultString(pull.getTitle()));
                livePRs.add(pull.getId());
                getPullRequestContributorCache().put(pull.getId(),
                        // TODO get more details on the author
                        new ContributorMetadataAction(pull.getAuthorLogin(), null, null)
                );
                observe(criteria, observer, listener,
                        pull.getSource().getRepository().getOwnerName(),
                        pull.getSource().getRepository().getRepositoryName(),
                        pull.getSource().getBranch().getName(),
                        hash,
                        head);
                if (!observer.isObserving()) {
                    return;
                }
            }
            getPullRequestTitleCache().keySet().retainAll(livePRs);
            getPullRequestContributorCache().keySet().retainAll(livePRs);
        } else {
            listener.getLogger().format("Skipping pull requests for public repositories%n");
        }
    }

    private void retrieveBranches(SCMSourceCriteria criteria, @NonNull final SCMHeadObserver observer,
                                  @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        String fullName = repoOwner + "/" + repository;
        listener.getLogger().println("Looking up " + fullName + " for branches");

        final BitbucketApi bitbucket = buildBitbucketClient();
        List<? extends BitbucketBranch> branches = bitbucket.getBranches();
        Set<SCMHead> includes = observer.getIncludes();

        for (BitbucketBranch b : branches) {
            SCMHead head = new BranchSCMHead(b.getName(), repositoryType);
            if (includes != null && !includes.contains(head)) {
                continue;
            }

            listener.getLogger().println("Checking branch " + b.getName() + " from " + fullName);
            observe(criteria, observer, listener, repoOwner, repository, b.getName(),
                    b.getRawNode(),
                    head
            );
            if (!observer.isObserving()) {
                break;
            }
        }

    }

    private void retrieveBranches(SCMSourceCriteria criteria, @NonNull final SCMHeadObserver observer,
                                  @NonNull TaskListener listener, @NonNull final BitbucketPushEvent push)
            throws IOException, InterruptedException {
        String fullName = repoOwner + "/" + repository;
        listener.getLogger().println("Looking up " + fullName + " for branches");

        final BitbucketApi bitbucket = buildBitbucketClient();
        List<? extends BitbucketBranch> branches = bitbucket.getBranches();
        Set<SCMHead> includes = observer.getIncludes();
        boolean isObserve = false;
        _CHANGES:
        for (BitbucketPushEvent.Change c : push.getChanges()) {
            isObserve = false;
            final BitbucketPushEvent.Reference target;
            SCMHead head;

            if (c.isCreated()) {
                //new

                target = c.getNew();
            } else {
                target = c.getOld();
            }

            BitbucketBranch b = new BitbucketBranch() {
                @Override
                public String getRawNode() {
                    return target.getTarget().getHash();
                }

                @Override
                public String getName() {
                    return target.getName();
                }
            };

            if ("tag".equals(target.getType())) {
                String defaultBranch = bitbucket.getDefaultBranch();
                isObserve = true;
                head = new BitbucketTagSCMHead(
                        target.getName(),
                        target.getTarget().getDate().getTime(),
                        target.getTarget().getHash()
                );
                String branchName = null;
                _REMOTE_TAGS:
                for(BitbucketTag remoteTag : bitbucket.getTags()){
                    if(remoteTag.getName().equals(target.getName())){
                        branchName = remoteTag.getBranch();
                        if(branchName == null && remoteTag.getBranches() != null && !remoteTag.getBranches().isEmpty()){
                            if(remoteTag.getBranches().contains(defaultBranch)){
                                branchName = defaultBranch;
                            }else{
                                branchName = remoteTag.getBranches().get(0);
                            }
                        }
                        break _REMOTE_TAGS;
                    }
                }



                if(branchName !=null){
                    if(!branchName.equals(bitbucket.getDefaultBranch())){
                        listener.getLogger().format("only allow tags on master branch, ignoring change for %s[%s]",target.getName(),target.getTarget().getHash());
                        continue _CHANGES;
                    }
                    _REMOTE_BRANCH:
                    for(BitbucketBranch remoteBranch: bitbucket.getBranches()){
                        if(remoteBranch.getName().equals(branchName)){
                            b = remoteBranch;
                            break _REMOTE_BRANCH;
                        }
                    }
                }

            } else {
                b = new BitbucketBranch() {
                    @Override
                    public String getRawNode() {
                        return target.getTarget().getHash();
                    }

                    @Override
                    public String getName() {
                        return target.getName();
                    }
                };
                for(BitbucketBranch remoteBranch: branches){
                    isObserve = isObserve || remoteBranch.hashCode() == b.hashCode();
                }
                head = new BranchSCMHead(target.getName(), repositoryType);
            }


            if (isObserve) {
                listener.getLogger().println("Checking branch " + target.getName() + " from " + fullName);
                observe(criteria, observer, listener, repoOwner, repository, b.getName(),
                        target.getTarget().getHash(),
                        head
                );
                if (!observer.isObserving()) {
                    break;
                }
            }

        }


    }


    private void observe(SCMSourceCriteria criteria, SCMHeadObserver observer, final TaskListener listener,
                         final String owner, final String repositoryName,
                         final String branchName, final String hash, SCMHead head) throws IOException, InterruptedException {
        if (isExcluded(branchName)) {
            return;
        }
        final BitbucketApi bitbucket = BitbucketApiFactory.newInstance(bitbucketServerUrl, getScanCredentials(), owner, repositoryName);

        if (criteria != null) {
            SCMSourceCriteria.Probe probe = new SCMSourceCriteria.Probe() {

                @Override
                public String name() {
                    return branchName;
                }

                @Override
                public long lastModified() {
                    try {
                        BitbucketCommit commit = bitbucket.resolveCommit(hash);
                        if (commit == null) {
                            listener.getLogger().format("Can not resolve commit by hash [%s] on repository %s/%s%n",
                                    hash, bitbucket.getOwner(), bitbucket.getRepositoryName());
                            return 0;
                        }
                        return commit.getDateMillis();
                    } catch (InterruptedException | IOException e) {
                        listener.getLogger().format("Can not resolve commit by hash [%s] on repository %s/%s%n",
                                hash, bitbucket.getOwner(), bitbucket.getRepositoryName());
                        return 0;
                    }
                }

                @Override
                public boolean exists(@NonNull String path) throws IOException {
                    try {
                        // TODO should be checking the revision not the head
                        return bitbucket.checkPathExists(branchName, path);
                    } catch (InterruptedException e) {
                        throw new IOException("Interrupted", e);
                    }
                }
            };
            if (criteria.isHead(probe, listener)) {
                listener.getLogger().println("Met criteria");
            } else {
                listener.getLogger().println("Does not meet criteria");
                return;
            }
        }
        BitbucketRepositoryType repositoryType = getRepositoryType();
        SCMRevision revision;
        if (repositoryType == BitbucketRepositoryType.MERCURIAL) {
            revision = new MercurialRevision(head, hash);
        } else {
            revision = new AbstractGitSCMSource.SCMRevisionImpl(head, hash);
        }
        observer.observe(head, revision);
    }


    @Override
    protected SCMRevision retrieve(SCMHead head, TaskListener listener) throws IOException, InterruptedException {
        BitbucketApi bitbucket = head instanceof PullRequestSCMHead
                ? buildBitbucketClient((PullRequestSCMHead) head)
                : buildBitbucketClient();
        if (head instanceof BitbucketTagSCMHead) {
            BitbucketTagSCMHead tHead = (BitbucketTagSCMHead) head;
            List<? extends BitbucketTag> tags = bitbucket.getTags();
            for(BitbucketTag t :tags){
                if (tHead.getName().equals(t.getName())){
                    if (t.getHash() == null) {
                        if (getBitbucketServerUrl() == null) {
                            listener.getLogger().format("Cannot resolve the hash of the revision in branch %s", t.getName());
                        } else {
                            listener.getLogger().format("Cannot resolve the hash of the revision in branch %s. Perhaps you are using Bitbucket Server previous to 4.x", t.getName());
                        }
                        return null;
                    }
                    if (getRepositoryType() == BitbucketRepositoryType.MERCURIAL) {
                        return new MercurialRevision(head, t.getHash());
                    } else {
                        return new AbstractGitSCMSource.SCMRevisionImpl(head, t.getHash());
                    }
                }
            }

        } else {
            String branchName = head instanceof PullRequestSCMHead ? ((PullRequestSCMHead) head).getBranchName() : head.getName();
            List<? extends BitbucketBranch> branches = bitbucket.getBranches();
            for (BitbucketBranch b : branches) {
                if (branchName.equals(b.getName())) {
                    if (b.getRawNode() == null) {
                        if (getBitbucketServerUrl() == null) {
                            listener.getLogger().format("Cannot resolve the hash of the revision in branch %s", b.getName());
                        } else {
                            listener.getLogger().format("Cannot resolve the hash of the revision in branch %s. Perhaps you are using Bitbucket Server previous to 4.x", b.getName());
                        }
                        return null;
                    }
                    if (getRepositoryType() == BitbucketRepositoryType.MERCURIAL) {
                        return new MercurialRevision(head, b.getRawNode());
                    } else {
                        return new AbstractGitSCMSource.SCMRevisionImpl(head, b.getRawNode());
                    }
                }
            }
        }
        LOGGER.log(Level.WARNING, "No branch found in {0}/{1} with name [{2}]", head instanceof PullRequestSCMHead
                ? new Object[]{
                ((PullRequestSCMHead) head).getRepoOwner(),
                ((PullRequestSCMHead) head).getRepository(),
                ((PullRequestSCMHead) head).getBranchName()}
                : new Object[]{repoOwner, repository, head.getName()});
        return null;
    }

    @Override
    public SCM build(SCMHead head, SCMRevision revision) {
        BitbucketRepositoryType repoType;
        if (head instanceof PullRequestSCMHead) {
            repoType = ((PullRequestSCMHead) head).getRepositoryType();
        } else if (head instanceof BranchSCMHead) {
            repoType = ((BranchSCMHead) head).getRepositoryType();
        } else if (head instanceof BitbucketTagSCMHead) {
            repoType = repositoryType;
        } else {
            throw new IllegalArgumentException("Either PullRequestSCMHead or BranchSCMHead required as parameter");
        }
        if (repoType == null) {
            if (revision instanceof MercurialRevision) {
                repoType = BitbucketRepositoryType.MERCURIAL;
            } else if (revision instanceof AbstractGitSCMSource.SCMRevisionImpl) {
                repoType = BitbucketRepositoryType.GIT;
            } else {
                try {
                    repoType = getRepositoryType();
                } catch (IOException | InterruptedException e) {
                    repoType = BitbucketRepositoryType.GIT;
                    LOGGER.log(Level.SEVERE,
                            "Could not determine repository type of " + getRepoOwner() + "/" + getRepository()
                                    + " on " + StringUtils.defaultIfBlank(getBitbucketServerUrl(), "bitbucket.org")
                                    + " for " + getOwner() + " assuming " + repoType, e);
                }
            }
        }
        if (head instanceof PullRequestSCMHead) {
            PullRequestSCMHead h = (PullRequestSCMHead) head;
            if (repoType == BitbucketRepositoryType.MERCURIAL) {
                MercurialSCM scm = new MercurialSCM(getRemote(h.getRepoOwner(), h.getRepository(),
                        BitbucketRepositoryType.MERCURIAL));
                // If no revision specified the branch name will be used as revision
                scm.setRevision(revision instanceof MercurialRevision
                        ? ((MercurialRevision) revision).getHash()
                        : h.getBranchName()
                );
                scm.setRevisionType(RevisionType.BRANCH);
                scm.setCredentialsId(getCheckoutEffectiveCredentials());
                return scm;
            } else {
                // Defaults to Git
                BuildChooser buildChooser = revision instanceof AbstractGitSCMSource.SCMRevisionImpl
                        ? new SpecificRevisionBuildChooser((AbstractGitSCMSource.SCMRevisionImpl) revision)
                        : new DefaultBuildChooser();
                return new GitSCM(getGitRemoteConfigs(h),
                        Collections.singletonList(new BranchSpec(h.getBranchName())),
                        false, Collections.<SubmoduleConfig>emptyList(),
                        null, null, Collections.<GitSCMExtension>singletonList(new BuildChooserSetting(buildChooser)));
            }
        } else {
            // head instanceof BranchSCMHead
            if (repoType == BitbucketRepositoryType.MERCURIAL) {
                MercurialSCM scm = new MercurialSCM(getRemote(repoOwner, repository, BitbucketRepositoryType.MERCURIAL));
                // If no revision specified the branch name will be used as revision
                scm.setRevision(revision instanceof MercurialRevision
                        ? ((MercurialRevision) revision).getHash()
                        : head.getName()
                );
                scm.setRevisionType(RevisionType.BRANCH);
                scm.setCredentialsId(getCheckoutEffectiveCredentials());
                return scm;
            } else {
                // Defaults to Git
                BuildChooser buildChooser = revision instanceof AbstractGitSCMSource.SCMRevisionImpl
                        ? new SpecificRevisionBuildChooser((AbstractGitSCMSource.SCMRevisionImpl) revision)
                        : new DefaultBuildChooser();
                if (head instanceof BranchSCMHead) {

                    return new GitSCM(getGitRemoteConfigs((BranchSCMHead) head),
                            Collections.singletonList(new BranchSpec(head.getName())),
                            false, Collections.<SubmoduleConfig>emptyList(),
                            null, null, Collections.<GitSCMExtension>singletonList(new BuildChooserSetting(buildChooser)));
                } else {
                    return new GitSCM(getGitRemoteConfigs((BitbucketTagSCMHead) head),
                            Collections.<BranchSpec>emptyList(),
                            false,
                            Collections.<SubmoduleConfig>emptyList(),
                            null, null,
                            Collections.<GitSCMExtension>singletonList(new BuildChooserSetting(buildChooser))
                    );
                }
            }
        }
    }

    protected List<UserRemoteConfig> getGitRemoteConfigs(BitbucketTagSCMHead head) {
        List<UserRemoteConfig> result = new ArrayList<UserRemoteConfig>();
        String remote = getRemote(repoOwner, repository, BitbucketRepositoryType.GIT);
        result.add(new UserRemoteConfig(remote, getRemoteName(), "+refs/tags/" + head.getName(), getCheckoutEffectiveCredentials()));
        return result;
    }

    protected List<UserRemoteConfig> getGitRemoteConfigs(BranchSCMHead head) {
        List<UserRemoteConfig> result = new ArrayList<UserRemoteConfig>();
        String remote = getRemote(repoOwner, repository, BitbucketRepositoryType.GIT);
        result.add(new UserRemoteConfig(remote, getRemoteName(), "+refs/heads/" + head.getName(), getCheckoutEffectiveCredentials()));
        return result;
    }

    protected List<UserRemoteConfig> getGitRemoteConfigs(PullRequestSCMHead head) {
        List<UserRemoteConfig> result = new ArrayList<UserRemoteConfig>();
        String remote = getRemote(head.getRepoOwner(), head.getRepository(), BitbucketRepositoryType.GIT);
        result.add(new UserRemoteConfig(remote, getRemoteName(), "+refs/heads/" + head.getBranchName(), getCheckoutEffectiveCredentials()));
        return result;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @CheckForNull
    /* package */ StandardUsernamePasswordCredentials getScanCredentials() {
        return BitbucketCredentials.lookupCredentials(
                bitbucketServerUrl,
                getOwner(),
                credentialsId,
                StandardUsernamePasswordCredentials.class
        );
    }

    private StandardCredentials getCheckoutCredentials() {
        return BitbucketCredentials.lookupCredentials(
                bitbucketServerUrl,
                getOwner(),
                getCheckoutEffectiveCredentials(),
                StandardCredentials.class
        );
    }

    public String getRemoteName() {
        return "origin";
    }

    /**
     * Returns true if the branchName isn't matched by includes or is matched by excludes.
     *
     * @param branchName
     * @return true if branchName is excluded or is not included
     */
    private boolean isExcluded(String branchName) {
        return !Pattern.matches(getPattern(getIncludes()), branchName)
                || Pattern.matches(getPattern(getExcludes()), branchName);
    }

    /**
     * Returns the pattern corresponding to the branches containing wildcards.
     *
     * @param branches space separated list of expressions.
     *                 For example "*" which would match all branches and branch* would match branch1, branch2, etc.
     * @return pattern corresponding to the branches containing wildcards (ready to be used by {@link Pattern})
     */
    private String getPattern(String branches) {
        StringBuilder quotedBranches = new StringBuilder();
        for (String wildcard : branches.split(" ")) {
            StringBuilder quotedBranch = new StringBuilder();
            for (String branch : wildcard.split("\\*")) {
                if (wildcard.startsWith("*") || quotedBranches.length() > 0) {
                    quotedBranch.append(".*");
                }
                quotedBranch.append(Pattern.quote(branch));
            }
            if (wildcard.endsWith("*")) {
                quotedBranch.append(".*");
            }
            if (quotedBranches.length() > 0) {
                quotedBranches.append("|");
            }
            quotedBranches.append(quotedBranch);
        }
        return quotedBranches.toString();
    }

    private String getCheckoutEffectiveCredentials() {
        if (DescriptorImpl.ANONYMOUS.equals(checkoutCredentialsId)) {
            return null;
        } else if (DescriptorImpl.SAME.equals(checkoutCredentialsId)) {
            return credentialsId;
        } else {
            return checkoutCredentialsId;
        }
    }

    @NonNull
    @Override
    protected List<Action> retrieveActions(@CheckForNull SCMSourceEvent event,
                                           @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        // TODO when we have support for trusted events, use the details from event if event was from trusted source
        List<Action> result = new ArrayList<>();
        final BitbucketApi bitbucket = buildBitbucketClient();
        BitbucketRepository r = bitbucket.getRepository();
        result.add(new BitbucketRepoMetadataAction(r));
        String defaultBranch = bitbucket.getDefaultBranch();
        if (StringUtils.isNotBlank(defaultBranch)) {
            result.add(new BitbucketDefaultBranch(repoOwner, repository, defaultBranch));
        }
        String serverUrl = StringUtils.removeEnd(bitbucketUrl(), "/");
        if (StringUtils.isNotEmpty(bitbucketServerUrl)) {
            result.add(new BitbucketLink("icon-bitbucket-repo",
                    serverUrl + "/projects/" + repoOwner + "/repos/" + repository));
            result.add(new ObjectMetadataAction(r.getFullName(), null,
                    serverUrl + "/projects/" + repoOwner + "/repos/" + repository));
        } else {
            result.add(new BitbucketLink("icon-bitbucket-repo", serverUrl + "/" + repoOwner + "/" + repository));
            result.add(new ObjectMetadataAction(r.getFullName(), null,
                    serverUrl + "/" + repoOwner + "/" + repository));
        }
        return result;
    }

    @NonNull
    @Override
    protected List<Action> retrieveActions(@NonNull SCMHead head,
                                           @CheckForNull SCMHeadEvent event,
                                           @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        // TODO when we have support for trusted events, use the details from event if event was from trusted source
        List<Action> result = new ArrayList<>();
        String serverUrl = StringUtils.removeEnd(bitbucketUrl(), "/");
        if (StringUtils.isNotEmpty(bitbucketServerUrl)) {
            String branchUrl;
            String title;
            if (head instanceof PullRequestSCMHead) {
                PullRequestSCMHead pr = (PullRequestSCMHead) head;
                branchUrl = "projects/" + repoOwner + "/repos/" + repository + "/pull-requests/" + pr.getId() + "/overview";
                title = getPullRequestTitleCache().get(pr.getId());
                ContributorMetadataAction contributor = getPullRequestContributorCache().get(pr.getId());
                if (contributor != null) {
                    result.add(contributor);
                }
            } else {
                branchUrl = "projects/" + repoOwner + "/repos/" + repository + "/compare/commits?sourceBranch=" +
                        URLEncoder.encode(Constants.R_HEADS + head.getName(), "UTF-8");
                title = null;
            }
            result.add(new BitbucketLink("icon-bitbucket-branch", serverUrl + "/" + branchUrl));
            result.add(new ObjectMetadataAction(title, null, serverUrl + "/" + branchUrl));
        } else {
            String branchUrl;
            String title;
            if (head instanceof PullRequestSCMHead) {
                PullRequestSCMHead pr = (PullRequestSCMHead) head;
                branchUrl = repoOwner + "/" + repository + "/pull-requests/" + pr.getId();
                title = getPullRequestTitleCache().get(pr.getId());
                ContributorMetadataAction contributor = getPullRequestContributorCache().get(pr.getId());
                if (contributor != null) {
                    result.add(contributor);
                }
            } else {
                branchUrl = repoOwner + "/" + repository + "/branch/" + head.getName();
                title = null;
            }
            result.add(new BitbucketLink("icon-bitbucket-branch", serverUrl + "/" + branchUrl));
            result.add(new ObjectMetadataAction(title, null, serverUrl + "/" + branchUrl));
        }
        SCMSourceOwner owner = getOwner();
        if (owner instanceof Actionable) {
            for (BitbucketDefaultBranch p : ((Actionable) owner).getActions(BitbucketDefaultBranch.class)) {
                if (StringUtils.equals(getRepoOwner(), p.getRepoOwner())
                        && StringUtils.equals(repository, p.getRepository())
                        && StringUtils.equals(p.getDefaultBranch(), head.getName())) {
                    result.add(new PrimaryInstanceMetadataAction());
                    break;
                }
            }
        }
        return result;
    }

    @NonNull
    private synchronized Map<String, String> getPullRequestTitleCache() {
        if (pullRequestTitleCache == null) {
            pullRequestTitleCache = new ConcurrentHashMap<>();
        }
        return pullRequestTitleCache;
    }

    @NonNull
    private synchronized Map<String, ContributorMetadataAction> getPullRequestContributorCache() {
        if (pullRequestContributorCache == null) {
            pullRequestContributorCache = new ConcurrentHashMap<>();
        }
        return pullRequestContributorCache;
    }

    @Extension
    public static class DescriptorImpl extends SCMSourceDescriptor {

        public static final String ANONYMOUS = "ANONYMOUS";
        public static final String SAME = "SAME";

        @Override
        public String getDisplayName() {
            return "Bitbucket";
        }

        public FormValidation doCheckCredentialsId(@QueryParameter String value,
                                                   @QueryParameter String bitbucketServerUrl) {
            if (!value.isEmpty()) {
                return FormValidation.ok();
            } else {
                return FormValidation.warning("Credentials are required for notifications");
            }
        }

        public static FormValidation doCheckBitbucketServerUrl(@QueryParameter String bitbucketServerUrl) {
            String url = Util.fixEmpty(bitbucketServerUrl);
            if (url == null) {
                return FormValidation.ok();
            }
            try {
                new URL(bitbucketServerUrl);
            } catch (MalformedURLException e) {
                return FormValidation.error("Invalid URL: " + e.getMessage());
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath SCMSourceOwner context, @QueryParameter String bitbucketServerUrl) {
            StandardListBoxModel result = new StandardListBoxModel();
            result.includeEmptyValue();
            return BitbucketCredentials.fillCredentials(bitbucketServerUrl, context, result);
        }

        public ListBoxModel doFillCheckoutCredentialsIdItems(@AncestorInPath SCMSourceOwner context, @QueryParameter String bitbucketServerUrl) {
            StandardListBoxModel result = new StandardListBoxModel();
            result.add("- same as scan credentials -", SAME);
            result.add("- anonymous -", ANONYMOUS);
            return BitbucketCredentials.fillCheckoutCredentials(bitbucketServerUrl, context, result);
        }

        @NonNull
        @Override
        protected SCMHeadCategory[] createCategories() {
            return new SCMHeadCategory[]{
                    new UncategorizedSCMHeadCategory(Messages._BitbucketSCMSource_UncategorizedSCMHeadCategory_DisplayName()),
                    new ChangeRequestSCMHeadCategory(Messages._BitbucketSCMSource_ChangeRequestSCMHeadCategory_DisplayName()),
                    new TagSCMHeadCategory(Messages._BitbucketSCMSource_TagSCMHeadCategory_DisplayName())
                    // TODO add support for tags and maybe feature branch identification
            };
        }
    }

    public static class MercurialRevision extends SCMRevision {

        private static final long serialVersionUID = 1L;

        private String hash;

        public MercurialRevision(SCMHead head, String hash) {
            super(head);
            this.hash = hash;
        }

        public String getHash() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            MercurialRevision that = (MercurialRevision) o;

            return StringUtils.equals(hash, that.hash) && getHead().equals(that.getHead());

        }

        @Override
        public int hashCode() {
            return hash != null ? hash.hashCode() : 0;
        }
    }

}
