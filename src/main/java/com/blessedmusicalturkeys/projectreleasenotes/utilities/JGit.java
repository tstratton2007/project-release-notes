package com.blessedmusicalturkeys.projectreleasenotes.utilities;

import com.blessedmusicalturkeys.projectreleasenotes.constants.ApplicationConstants;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;
import org.eclipse.jgit.util.FS;

/**
 * Class that connects to the provided GIT repository and performs GIT operations on it
 *
 * @author Timothy Stratton
 */
public class JGit {

  private final String gitPrivateKey;

  private final String CONST_MERGE_PREAMBLE = "Merged in";

  private final Set<String> typicalGitFolders = Set
      .of("", //no folder
          "feature/", "features/",
          "hotfix/", "hotfixes/",
          "fix/", "fixes/",
          "bug/", "bugs/",
          "bugfix/", "bugfixes/",
          "release/", "releases/");

  private final Git git;
  private final File workingDir;

  public JGit() throws IOException, GitAPIException {
    this.gitPrivateKey = ApplicationConstants.CONST_GIT_REPOSITORY_PRIVATE_KEY;

    this.workingDir = Files.createTempDirectory("workspace").toFile();

    git = Git.cloneRepository()
      .setDirectory(workingDir)
      .setTransportConfigCallback(new SshTransportConfigCallback())
      .setURI(ApplicationConstants.CONST_GIT_REPOSITORY_URL)
      .call();

    git.checkout().setName(ApplicationConstants.CONST_GIT_WORKING_TRUNK_TO_BRANCH_FROM).call();
  }

  public List<String> listTags() throws MissingObjectException, GitAPIException {
    Set<String> tags = new LinkedHashSet<>();

    Iterable<RevCommit> commits = git.log().call();
    for (RevCommit commit : commits) {
      Map<ObjectId, String> namedCommits = git.nameRev().addPrefix("refs/tags/").add(commit).call();
      if (namedCommits.containsKey(commit.getId()) && !namedCommits.get(commit.getId())
          .contains("~")) {
        tags.add(namedCommits.get(commit.getId()));
      }
    }

    return new ArrayList<>(tags);
  }

  public List<String> getAllIssuesSenseLastTag()
      throws GitAPIException, MissingObjectException {
    Set<String> issues = new LinkedHashSet<>();
    Date dateOfLastTag = null;

    //retrieve the date of the last tag
    Iterable<RevCommit> commits = git.log().call();
    for (RevCommit commit : commits) {
      Map<ObjectId, String> namedCommits = git.nameRev().addPrefix("refs/tags/").add(commit).call();
      if (namedCommits.containsKey(commit.getId())) {
        dateOfLastTag = commit.getAuthorIdent().getWhen();
        break;
      }
    }

    // get all merged issues since the last tag
    commits = git.log().call();
    for (RevCommit commit : commits) {
      if (commit.getAuthorIdent().getWhen().compareTo(dateOfLastTag) > 0
          && commit.getShortMessage().contains(CONST_MERGE_PREAMBLE)
          && commit.getShortMessage().contains(ApplicationConstants.CONST_JIRA_PROJECT_KEY)) {
        issues.add(parseIssueKeyFromCommit(commit));
      }
    }

    return new ArrayList<>(issues);
  }

  public List<String> getIssuesWithinTag(String tagName)
      throws GitAPIException, MissingObjectException {
    Set<String> issues = new LinkedHashSet<>();

    Iterable<RevCommit> commits = git.log().call();
    for (RevCommit commit : commits) {
      Map<ObjectId, String> namedCommits = git.nameRev().addPrefix("refs/tags/").add(commit).call();
      if (namedCommits.containsKey(commit.getId())
          && commit.getShortMessage().contains(CONST_MERGE_PREAMBLE)
          && namedCommits.get(commit.getId()).contains(tagName)
      ) {
        issues.add(parseIssueKeyFromCommit(commit));
      }
    }

    return new ArrayList<>(issues);
  }

  private String parseIssueKeyFromCommit(RevCommit commit) {
    String unparsedIssueString;

    for (String gitFolder : typicalGitFolders) {
      try {
        unparsedIssueString = commit.getShortMessage()
            .split(CONST_MERGE_PREAMBLE + " " + gitFolder + ApplicationConstants.CONST_JIRA_PROJECT_KEY)[1];

        return ApplicationConstants.CONST_JIRA_PROJECT_KEY + "-" + unparsedIssueString.split("-")[1];
      } catch (IndexOutOfBoundsException e) { /* nothing to be done, continue */ }
    }

    System.out.println("Unable to parse Issue Number from commit: [" + commit.getShortMessage() + "]");
    throw new RuntimeException("Unsupported Git Folder Structure");
  }

  public void commitChangelogTagAndPush(String releaseName) throws GitAPIException, IOException {
    String changelogBranchName = checkoutChangelogBranchCommitAndTag(releaseName);

    mergeChangelogBranchIntoWorkingTrunk(changelogBranchName);

    git.push()
        .setPushTags()
        .setTransportConfigCallback(new SshTransportConfigCallback())
        .call();
  }

  public void mergeChangelogBranchIntoWorkingTrunk(String changelogBranchName)
      throws IOException, GitAPIException {
    CheckoutCommand checkoutCmd = git.checkout();
    checkoutCmd.setName(ApplicationConstants.CONST_GIT_WORKING_TRUNK_TO_BRANCH_FROM);
    checkoutCmd.setCreateBranch(false);
    checkoutCmd.call();

    ObjectId mergeBase = git.getRepository().resolve(changelogBranchName);

    MergeResult merge = git.merge()
        .include(mergeBase)
        .setCommit(true)
        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
        .setMessage("Merged in [" + changelogBranchName + "] to " + ApplicationConstants.CONST_GIT_WORKING_TRUNK_TO_BRANCH_FROM)
        .call();

    if (merge.getConflicts() != null) {//should not have conflict b/c of this trivial change
      for (Map.Entry<String, int[][]> entry : merge.getConflicts().entrySet()) {
        System.out.println("Key: " + entry.getKey());
        for (int[] arr : entry.getValue()) {
          System.out.println("value: " + Arrays.toString(arr));
        }
      }
    }
  }

  private String checkoutChangelogBranchCommitAndTag(String releaseName) throws GitAPIException {
    String changelogBranchName = "update-changelog-" + new Date().getTime();
    git.checkout().setCreateBranch(true).setName(changelogBranchName).call();

    git.add().addFilepattern("changelog").call();

    CommitCommand commitCommand = git.commit();
    commitCommand.setAuthor("project-release-notes", "no@no.com");
    commitCommand.setMessage("Generated Changelog for release [" + releaseName + "] at [" + LocalDateTime.now() + "]");
    RevCommit commit = commitCommand.call();

    TagCommand tagCommand = git.tag();
    tagCommand.setObjectId(commit);
    tagCommand.setName(releaseName);
    tagCommand.call();

    return changelogBranchName;
  }

  public File getWorkingDir() {
    return this.workingDir;
  }

  private class SshTransportConfigCallback implements TransportConfigCallback {

    private final SshSessionFactory sshSessionFactory = new JschConfigSessionFactory() {
      @Override
      protected void configure(OpenSshConfig.Host hc, Session session) {
        session.setConfig("StrictHostKeyChecking", "no");
      }

      @Override
      protected JSch createDefaultJSch(FS fs) throws JSchException {
        File privateKeyFile = createPrivateKeyFile();
        JSch jSch = super.createDefaultJSch(fs);
        jSch.removeAllIdentity();
        jSch.addIdentity(privateKeyFile.getAbsolutePath());
        return jSch;
      }
    };

    private File createPrivateKeyFile() {
      try {
        File privateKeyFile = Files.createTempFile("id_rsa", "").toFile();
        FileOutputStream outputStream = new FileOutputStream(privateKeyFile);
        byte[] strToBytes = gitPrivateKey.getBytes();
        outputStream.write(strToBytes);
        outputStream.close();
        return privateKeyFile;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void configure(Transport transport) {
      SshTransport sshTransport = (SshTransport) transport;
      sshTransport.setSshSessionFactory(sshSessionFactory);
    }

  }
}
