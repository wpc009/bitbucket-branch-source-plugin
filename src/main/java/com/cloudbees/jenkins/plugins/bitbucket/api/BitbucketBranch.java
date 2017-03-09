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
package com.cloudbees.jenkins.plugins.bitbucket.api;

/**
 * Bitbucket Branch.
 *
 * It's used to represent branches to be built and source branches for pull requests.
 */
public abstract class BitbucketBranch {

    /**
     * @return the head commit node of this branch
     */
    abstract public String getRawNode();

    /**
     * @return the branch name
     */
    abstract public String getName();

    @Override
    public int hashCode() {
        return (this.getRawNode() + this.getRawNode()).hashCode();
    }

    @Override
    public String toString() {
        return this.getName() + ":" + this.getRawNode();
    }

    @Override
    public boolean equals(Object obj) {
        if(obj instanceof BitbucketBranch){
            BitbucketBranch b = (BitbucketBranch) obj;
            return this.hashCode() == b.hashCode();
        }else{
            return false;
        }
    }


}