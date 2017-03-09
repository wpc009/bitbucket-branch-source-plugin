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
package com.cloudbees.jenkins.plugins.bitbucket.hooks;

import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMNavigator;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketTagSCMHead;
import com.cloudbees.jenkins.plugins.bitbucket.BranchSCMHead;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketBranch;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketHref;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketPushEvent;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepositoryType;
import com.cloudbees.jenkins.plugins.bitbucket.client.BitbucketCloudWebhookPayload;
import com.cloudbees.jenkins.plugins.bitbucket.client.events.BitbucketCloudPushEvent;
import com.cloudbees.jenkins.plugins.bitbucket.server.client.BitbucketServerWebhookPayload;

import com.cloudbees.jenkins.plugins.bitbucket.server.events.BitbucketServerPushEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.scm.RepositoryBrowser;
import hudson.scm.SCM;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import org.codehaus.jackson.map.ObjectMapper;

public class PushHookProcessor extends HookProcessor {

    private static final Logger LOGGER = Logger.getLogger(PushHookProcessor.class.getName());

    @Override
    public void process(HookEventType hookEvent, String payload, BitbucketType instanceType, String origin) {
        if (payload != null) {
            BitbucketPushEvent push;
            if (instanceType == BitbucketType.SERVER) {
                push = BitbucketServerWebhookPayload.pushEventFromPayload(payload);
            } else {
                push = BitbucketCloudWebhookPayload.pushEventFromPayload(payload);
            }
            if (push != null) {
                String owner = push.getRepository().getOwnerName();
                final String repository = push.getRepository().getRepositoryName();
                if (push.getChanges().isEmpty()) {
                    LOGGER.log(Level.INFO, "Received hook from Bitbucket. Processing push event on {0}/{1}",
                            new Object[]{owner, repository});
                    scmSourceReIndex(owner, repository);
                } else {
                    SCMHeadEvent.Type type = null;
                    for (BitbucketPushEvent.Change change : push.getChanges()) {
                        if ((type == null || type == SCMEvent.Type.CREATED) && change.isCreated()) {
                            type = SCMEvent.Type.CREATED;
                        } else if ((type == null || type == SCMEvent.Type.REMOVED) && change.isClosed()) {
                            type = SCMEvent.Type.REMOVED;
                        } else {
                            type = SCMEvent.Type.UPDATED;
                        }
                    }
                    SCMHeadEvent.fireNow(new SCMHeadEvent<BitbucketPushEvent>(type, push, origin) {
                        @Override
                        public boolean isMatch(@NonNull SCMNavigator navigator) {
                            if (!(navigator instanceof BitbucketSCMNavigator)) {
                                return false;
                            }
                            BitbucketSCMNavigator bbNav = (BitbucketSCMNavigator) navigator;
                            if (!isBitbucketServerUrlMatch(bbNav.getBitbucketServerUrl())) {
                                return false;
                            }
                            if (!bbNav.getRepoOwner().equalsIgnoreCase(getPayload().getRepository().getOwnerName())) {
                                return false;
                            }
                            return true;
                        }

                        private boolean isBitbucketServerUrlMatch(String serverUrl) {
                            if (serverUrl == null) {
                                // this is a Bitbucket cloud navigator
                                if (getPayload() instanceof BitbucketServerPushEvent) {
                                    return false;
                                }
                            } else {
                                // this is a Bitbucket server navigator
                                if (getPayload() instanceof BitbucketCloudPushEvent) {
                                    return false;
                                }
                                Map<String, BitbucketHref> links = getPayload().getRepository().getLinks();
                                if (links != null && links.containsKey("self")) {
                                    try {
                                        URI navUri = new URI(serverUrl);
                                        URI evtUri = new URI(links.get("self").getHref());
                                        if (!navUri.getHost().equalsIgnoreCase(evtUri.getHost())) {
                                            // from a different Bitbucket server
                                            return false;
                                        }
                                    } catch (URISyntaxException e) {
                                        // ignore
                                    }
                                }
                            }
                            return true;
                        }

                        @NonNull
                        @Override
                        public String getSourceName() {
                            return getPayload().getRepository().getRepositoryName();
                        }

                        @NonNull
                        @Override
                        public Map<SCMHead, SCMRevision> heads(@NonNull SCMSource source) {
                            if (!(source instanceof BitbucketSCMSource)) {
                                return Collections.emptyMap();
                            }
                            BitbucketSCMSource src = (BitbucketSCMSource) source;
                            if (!isBitbucketServerUrlMatch(src.getBitbucketServerUrl())) {
                                return Collections.emptyMap();
                            }
                            if (!src.getRepoOwner().equalsIgnoreCase(getPayload().getRepository().getOwnerName())) {
                                return Collections.emptyMap();
                            }
                            if (!src.getRepository().equalsIgnoreCase(getPayload().getRepository().getRepositoryName())) {
                                return Collections.emptyMap();
                            }
                            BitbucketRepositoryType type = BitbucketRepositoryType.fromString(
                                    getPayload().getRepository().getScm());
                            if (type == null) {
                                LOGGER.log(Level.INFO, "Received event for unknown repository type: {0}",
                                        getPayload().getRepository().getScm());
                                return Collections.emptyMap();
                            }
                            Map<SCMHead, SCMRevision> result = new HashMap<>();
                            for (BitbucketPushEvent.Change change : getPayload().getChanges()) {


                                if (change.isClosed()) {
                                    switch (change.getOld().getType()) {
                                        case "tag":
                                            result.put(new BitbucketTagSCMHead(change.getOld().getName(), change.getOld().getTarget().getDate().getTime(),change.getOld().getTarget().getHash()), null);
                                            break;
                                        case "branch":
                                            result.put(new BranchSCMHead(change.getOld().getName(), type), null);
                                            break;
                                        default:
                                            break;

                                    }
                                } else {
                                    SCMHead head = new BranchSCMHead(change.getNew().getName(), type);
                                    switch (change.getNew().getType()) {
                                        case "tag":
                                            head = new BitbucketTagSCMHead(change.getNew().getName(),change.getNew().getTarget().getDate().getTime(),change.getNew().getTarget().getHash());
                                            break;
                                        default:
                                            break;
                                    }

                                    switch (type) {
                                        case GIT:
                                            result.put(head, new AbstractGitSCMSource.SCMRevisionImpl(head, change.getNew().getTarget().getHash()));
                                            break;
                                        case MERCURIAL:
                                            result.put(head, new BitbucketSCMSource.MercurialRevision(head, change.getNew().getTarget().getHash()));
                                            break;
                                        default:
                                            LOGGER.log(Level.INFO, "Received event for unknown repository type: {0}", type);
                                            break;
                                    }
                                }
                            }
                            return result;
                        }

                        @Override
                        public boolean isMatch(@NonNull SCM scm) {
                            // TODO
                            return false;
                        }
                    });
                }
            }
        }
    }
}
