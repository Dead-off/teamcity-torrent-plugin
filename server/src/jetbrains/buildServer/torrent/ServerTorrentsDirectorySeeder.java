/*
 * Copyright (c) 2000-2012 by JetBrains s.r.o. All Rights Reserved.
 * Use is subject to license terms.
 */
package jetbrains.buildServer.torrent;

import com.intellij.openapi.diagnostic.Logger;
import com.turn.ttorrent.client.SharedTorrent;
import jetbrains.buildServer.ArtifactsConstants;
import jetbrains.buildServer.NetworkUtil;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifact;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifacts;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifactsViewMode;
import jetbrains.buildServer.torrent.seeder.ParentDirConverter;
import jetbrains.buildServer.torrent.seeder.TorrentsSeeder;
import jetbrains.buildServer.torrent.torrent.TorrentUtil;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Maxim Podkolzine (maxim.podkolzine@jetbrains.com)
 * @since 8.0
 */
public class ServerTorrentsDirectorySeeder {
  private final static Logger LOG = Logger.getInstance(ServerTorrentsDirectorySeeder.class.getName());

  private TorrentsSeeder myTorrentsSeeder;
  private final TorrentConfigurator myConfigurator;
  private URI myAnnounceURI;
  private int myMaxTorrentsToSeed;

  public ServerTorrentsDirectorySeeder(@NotNull final ServerPaths serverPaths,
                                       @NotNull final ServerSettings serverSettings,
                                       @NotNull final TorrentConfigurator configurator,
                                       @NotNull final EventDispatcher<BuildServerListener> eventDispatcher) {
    setMaxNumberOfSeededTorrents(configurator.getMaxNumberOfSeededTorrents());
    myConfigurator = configurator;
    eventDispatcher.addListener(new BuildServerAdapter() {
      @Override
      public void serverStartup() {
        final File torrentsStorage = new File(serverPaths.getPluginDataDirectory(), "torrents");
        torrentsStorage.mkdirs();
        myTorrentsSeeder = new TorrentsSeeder(torrentsStorage, configurator.getMaxNumberOfSeededTorrents(), new ParentDirConverter() {
          @NotNull
          @Override
          public File getParentDir() {
            return serverSettings.getArtifactDirectories().get(0);
          }
        });

        // if torrent file expires, it will be removed from disk as well
        // this is needed to prevent agents from downloading this torrent file (because most likely no one is going to seed this torrent in the future)
        // and to stop showing torrent icons for users
        myTorrentsSeeder.setRemoveExpiredTorrentFiles(true);

        if (myConfigurator.isTorrentEnabled()) {
          startSeeder();
        }
      }

      @Override
      public void buildFinished(@NotNull SRunningBuild build) {
        if (myConfigurator.isTorrentEnabled()) {
          announceBuildArtifacts(build);
        }
      }

      public void serverShutdown() {
        if (myTorrentsSeeder != null) {
          myTorrentsSeeder.dispose();
        }
      }

    });

    configurator.addPropertyChangeListener(new PropertyChangeListener() {
      public void propertyChange(PropertyChangeEvent evt) {
        String propertyName = evt.getPropertyName();
        if (TorrentConfiguration.MAX_NUMBER_OF_SEEDED_TORRENTS.equals(propertyName)) {
          setMaxNumberOfSeededTorrents((Integer) evt.getNewValue());
          myTorrentsSeeder.setMaxTorrentsToSeed(myMaxTorrentsToSeed);
        } else if (TorrentConfiguration.ANNOUNCE_INTERVAL.equals(propertyName)) {
          myTorrentsSeeder.setAnnounceInterval((Integer) evt.getNewValue());
        } else if (TorrentConfiguration.ANNOUNCE_URL.equals(propertyName)) {
          setAnnounceURI(URI.create(String.valueOf(evt.getNewValue())));
        } else if (TorrentConfiguration.DOWNLOAD_ENABLED.equals(propertyName) || TorrentConfiguration.TRANSPORT_ENABLED.equals(propertyName)) {
          boolean enabled = (Boolean) evt.getNewValue();
          if (enabled) {
            startSeeder();
          } else {
            stopSeeder();
          }
        }
      }
    });
  }


  public void stopSeeder() {
    myTorrentsSeeder.stop();
  }

  private void startSeeder() {
    try {

      final InetAddress[] addresses;
      if (myConfigurator.getOwnAddress() != null) {
        addresses = new InetAddress[]{InetAddress.getByName(myConfigurator.getOwnAddress())};
      } else {
        addresses = NetworkUtil.getSelfAddresses(null);
      }

      myTorrentsSeeder.start(addresses, myAnnounceURI, myConfigurator.getAnnounceIntervalSec());
    } catch (Exception e) {
      Loggers.SERVER.warn("Failed to start torrent seeder", e);
    }
  }

  @NotNull
  public File getTorrentFilesBaseDir(@NotNull SBuild build) {
    final File artifactsDirectory = build.getArtifactsDirectory();
    return new File(artifactsDirectory, TorrentsSeeder.TORRENTS_DIT_PATH);
  }

  @NotNull
  public Collection<File> getTorrentFiles(@NotNull SBuild build) {
    File baseDir = getTorrentFilesBaseDir(build);
    try {
      return FileUtil.findFiles(new FileFilter() {
        public boolean accept(File file) {
          return file.getName().endsWith(TorrentUtil.TORRENT_FILE_SUFFIX);
        }
      }, baseDir);
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }

  @NotNull
  public File getTorrentFile(@NotNull SBuild build, @NotNull String torrentPath) {
    return new File(getTorrentFilesBaseDir(build), torrentPath);
  }

  public int getNumberOfSeededTorrents() {
    if (myTorrentsSeeder.isStopped()) {
      return 0;
    }
    return myTorrentsSeeder.getNumberOfSeededTorrents();
  }

  private void announceBuildArtifacts(@NotNull final SBuild build) {
    final File torrentsDir = getTorrentFilesBaseDir(build);
    BuildArtifacts artifacts = build.getArtifacts(BuildArtifactsViewMode.VIEW_INTERNAL_ONLY);
    final File artifactsDirectory = build.getArtifactsDirectory();
    torrentsDir.mkdirs();
    List<String> artifactRelativePaths = new ArrayList<>();
    artifacts.iterateArtifacts(new BuildArtifacts.BuildArtifactsProcessor() {
      @NotNull
      public Continuation processBuildArtifact(@NotNull BuildArtifact artifact) {
        if (artifact.getName().equals(ArtifactsConstants.TEAMCITY_ARTIFACTS_DIR)) {
          return Continuation.SKIP_CHILDREN;
        }
        if (artifact.isFile()) {
          artifactRelativePaths.add(artifact.getRelativePath());
        }
        processArtifactInternal(artifact, artifactsDirectory, torrentsDir);
        return BuildArtifacts.BuildArtifactsProcessor.Continuation.CONTINUE;
      }
    });
    removeTorrentFilesWithoutArtifacts(artifactRelativePaths, torrentsDir);
  }

  private void removeTorrentFilesWithoutArtifacts(@NotNull final List<String> artifactRelativePaths,
                                                  @NotNull final File torrentsDir) {
    Collection<File> torrentsForRemoving = new ArrayList<>();
    try {
      Files.walkFileTree(torrentsDir.toPath(), new SimpleFileVisitor<Path>() {
        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
          for (String artifactPath : artifactRelativePaths) {
            if (path.endsWith(artifactPath + TorrentUtil.TORRENT_FILE_SUFFIX)) {
              return FileVisitResult.CONTINUE;
            }
          }
          torrentsForRemoving.add(path.toFile());
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      LOG.error("failed walk torrent files tree for removing useless torrents", e);
    }
    torrentsForRemoving.forEach(file -> file.delete());
  }

  protected void processArtifactInternal(@NotNull final BuildArtifact artifact,
                                         @NotNull final File artifactsDirectory,
                                         @NotNull final File torrentsDir) {
    if (artifact.isDirectory()) {
      return;
    }

    if (shouldCreateTorrentFor(artifact)) {
      File artifactFile = new File(artifactsDirectory, artifact.getRelativePath());
      if (!artifactFile.exists()) {
        LOG.debug(String.format("File '%s' doesn't exist. Won't create a torrent for it", artifactFile.getAbsolutePath()));
        return;
      }
      File torrentFile = createTorrent(artifactFile, artifact.getRelativePath(), torrentsDir);
      if (torrentFile != null) {
        myTorrentsSeeder.registerSrcAndTorrentFile(artifactFile, torrentFile, myConfigurator.isTorrentEnabled());
      }
    }
  }

  @Nullable
  private File createTorrent(@NotNull final File artifactFile,
                             @NotNull final String artifactPath,
                             @NotNull final File torrentsDir) {
    File destPath = new File(torrentsDir, artifactPath);
    final File parentDir = destPath.getParentFile();
    parentDir.mkdirs();

    return TorrentUtil.getOrCreateTorrent(artifactFile, artifactPath, torrentsDir, myAnnounceURI);
  }

  private boolean shouldCreateTorrentFor(@NotNull BuildArtifact artifact) {
    return TorrentUtil.shouldCreateTorrentFor(artifact.getSize(), myConfigurator);
  }

  public void setMaxNumberOfSeededTorrents(int maxNumberOfSeededTorrents) {
    myMaxTorrentsToSeed = maxNumberOfSeededTorrents;
  }

  public void setAnnounceURI(URI announceURI) {
    myAnnounceURI = announceURI;
  }

  public Collection<SharedTorrent> getSharedTorrents() {
    return myTorrentsSeeder.getSharedTorrents();
  }

  //for tests
  TorrentsSeeder getTorrentsSeeder() {
    return myTorrentsSeeder;
  }
}
