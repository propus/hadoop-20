package org.apache.hadoop.hdfs.server.namenode;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FsShell;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSCluster.DataNodeProperties;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.common.HdfsConstants;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem.NumberReplicas;

import junit.framework.TestCase;

public class TestUnderReplicatedBlocks extends TestCase {
  static final Log LOG = LogFactory.getLog(TestUnderReplicatedBlocks.class);
  static final String HOST_FILE_PATH = "/tmp/exclude_file_test_underreplicated_blocks";
  
  public void testSetrepIncWithUnderReplicatedBlocks() throws Exception {
    Configuration conf = new Configuration();
    final short REPLICATION_FACTOR = 2;
    final String FILE_NAME = "/testFile";
    final Path FILE_PATH = new Path(FILE_NAME);
    MiniDFSCluster cluster = new MiniDFSCluster(conf, REPLICATION_FACTOR+1, true, null);
    try {
      // create a file with one block with a replication factor of 2
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, FILE_PATH, 1L, REPLICATION_FACTOR, 1L);
      DFSTestUtil.waitReplication(fs, FILE_PATH, REPLICATION_FACTOR);
      
      // remove one replica from the blocksMap so block becomes under-replicated
      // but the block does not get put into the under-replicated blocks queue
      FSNamesystem namesystem = cluster.getNameNode().namesystem;
      Block b = DFSTestUtil.getFirstBlock(fs, FILE_PATH);
      DatanodeDescriptor dn = namesystem.blocksMap.nodeIterator(b).next();
      namesystem.addToInvalidates(b, dn, true);
      namesystem.blocksMap.removeNode(b, dn);
      
      // increment this file's replication factor
      FsShell shell = new FsShell(conf);
      assertEquals(0, shell.run(new String[]{
          "-setrep", "-w", Integer.toString(1+REPLICATION_FACTOR), FILE_NAME}));
    } finally {
      cluster.shutdown();
    }
    
  }
  
  private DataNodeProperties shutdownDataNode(MiniDFSCluster cluster, DatanodeDescriptor datanode) {
    LOG.info("shutdown datanode: " + datanode.getName());
    DataNodeProperties dnprop = cluster.stopDataNode(datanode.getName());
    FSNamesystem namesystem = cluster.getNameNode().namesystem;
    // make sure that NN detects that the datanode is down
    synchronized (namesystem.heartbeats) {
      datanode.setLastUpdate(0); // mark it dead
      namesystem.heartbeatCheck();
    }
    return dnprop;
  }
  
  public void testLostDataNodeAfterDeleteExcessReplica() throws Exception {
    final Configuration conf = new Configuration();
    final short REPLICATION_FACTOR = (short)4;
    final MiniDFSCluster cluster = 
      new MiniDFSCluster(conf, 5, true, null);
    try {
      final FSNamesystem namesystem = cluster.getNameNode().namesystem;
      final FileSystem fs = cluster.getFileSystem();
      DatanodeDescriptor[] datanodes = (DatanodeDescriptor[])
          namesystem.heartbeats.toArray(new DatanodeDescriptor[REPLICATION_FACTOR]);
      DataNodeProperties dnprop = shutdownDataNode(cluster, datanodes[4]);
      
      LOG.info("Start the cluster");
      // populate the cluster with a one block file
      final Path FILE_PATH = new Path("/testfile1");
      DFSTestUtil.createFile(fs, FILE_PATH, 1L, REPLICATION_FACTOR, 1L);
      DFSTestUtil.waitReplication(fs, FILE_PATH, REPLICATION_FACTOR);
      Block block = DFSTestUtil.getFirstBlock(fs, FILE_PATH);

      LOG.info("Setting replication to 3");
      fs.setReplication(FILE_PATH, (short)3);
      // check if excessive replica is detected
      waitForExcessReplicasToChange(namesystem, block, 1);

      LOG.info("Bring down two non-excess nodes");

      //bring down two non-excess node
      Iterator<DatanodeDescriptor> iter = namesystem.blocksMap.nodeIterator(block);
      DatanodeDescriptor nonExcessDN[] = new DatanodeDescriptor[2];
      DatanodeDescriptor excessDN = null;
      int limit = 0;
      while (iter.hasNext()) {
        DatanodeDescriptor dn = iter.next();
        Collection<Block> blocks = namesystem.excessReplicateMap.get(dn.getStorageID());
        if (blocks == null || !blocks.contains(block) ) {
          if (limit == 2) {
            continue;
          }
          nonExcessDN[limit++] = dn;
        } else {
          excessDN = dn;
        }
      }
      for (DatanodeDescriptor dn: nonExcessDN) {
        // bring down non excessive datanode
        LOG.info("Stopping non-excess node: " + dn);
        shutdownDataNode(cluster, dn);
      }
      LOG.info("Schedule blockReceivedAndDeleted report: " + excessDN);
      int index = cluster.findDataNodeIndex(excessDN.getName());
      DataNode dn = cluster.getDataNodes().get(index);
      Thread.sleep(3000);
      dn.scheduleNSBlockReceivedAndDeleted(0);
      Thread.sleep(3000);
      LOG.info("Start a new node");
      cluster.restartDataNode(dnprop);
      cluster.waitActive(false);
      // The block should be replicated
      LOG.info("wait for the block to replicate");
      NumberReplicas num;
      long startTime = System.currentTimeMillis();
      do {
       namesystem.readLock();
       try {
         num = namesystem.countNodes(block);
         namesystem.metaSave("TestLostDataNodeAfterDeleteExcessReplica.meta");
       } finally {
         namesystem.readUnlock();
       }
       Thread.sleep(1000);
       LOG.info("live: " + num.liveReplicas());
      } while (num.liveReplicas() != 3 && 
          System.currentTimeMillis() - startTime < 30000);
      assertEquals("Live Replicas doesn't reach 3", num.liveReplicas(), 3);
    } finally {
      cluster.shutdown();
    }
  }
  
  public void testDecommissionDataNodeAfterDeleteExcessReplica() throws Exception {
    final Configuration conf = new Configuration();
    final short REPLICATION_FACTOR = (short)4;
    File f = new File(HOST_FILE_PATH);
    if (f.exists()) {
      f.delete();
    }
    conf.set("dfs.hosts.exclude", HOST_FILE_PATH);
    final MiniDFSCluster cluster = 
      new MiniDFSCluster(conf, 5, true, null);
    try {
      final FSNamesystem namesystem = cluster.getNameNode().namesystem;
      final FileSystem fs = cluster.getFileSystem();
      DatanodeDescriptor[] datanodes = (DatanodeDescriptor[])
          namesystem.heartbeats.toArray(new DatanodeDescriptor[REPLICATION_FACTOR]);
      DataNodeProperties dnprop = shutdownDataNode(cluster, datanodes[4]);
      
      LOG.info("Start the cluster");
      // populate the cluster with a one block file
      final Path FILE_PATH = new Path("/testfile2");
      DFSTestUtil.createFile(fs, FILE_PATH, 1L, REPLICATION_FACTOR, 1L);
      DFSTestUtil.waitReplication(fs, FILE_PATH, REPLICATION_FACTOR);
      Block block = DFSTestUtil.getFirstBlock(fs, FILE_PATH);

      LOG.info("Setting replication to 3");
      fs.setReplication(FILE_PATH, (short)3);
      // check if excessive replica is detected
      waitForExcessReplicasToChange(namesystem, block, 1);

      Iterator<DatanodeDescriptor> iter = namesystem.blocksMap.nodeIterator(block);
      DatanodeDescriptor nonExcessDN[] = new DatanodeDescriptor[2];
      DatanodeDescriptor excessDN = null;
      int limit = 0;
      while (iter.hasNext()) {
        DatanodeDescriptor dn = iter.next();
        Collection<Block> blocks = namesystem.excessReplicateMap.get(dn.getStorageID());
        if (blocks == null || !blocks.contains(block) ) {
          if (limit == 2) {
            continue;
          }
          nonExcessDN[limit++] = dn;
        } else {
          excessDN = dn;
        }
      }
      NameNode nn = cluster.getNameNode();
      LOG.info("Decommission non-excess nodes: " + 
          nonExcessDN[0] + " " + nonExcessDN[1]);
      addToExcludeFile(nn.getConf(), nonExcessDN);
      namesystem.refreshNodes(nn.getConf());
      LOG.info("Schedule blockReceivedAndDeleted report: " + excessDN);
      int index = cluster.findDataNodeIndex(excessDN.getName());
      DataNode dn = cluster.getDataNodes().get(index);
      Thread.sleep(3000);
      dn.scheduleNSBlockReceivedAndDeleted(0);
      Thread.sleep(3000);
      LOG.info("Start a new node");
      cluster.restartDataNode(dnprop);
      cluster.waitActive(false);
      // The block should be replicated
      LOG.info("wait for the block to replicate");
      NumberReplicas num;
      long startTime = System.currentTimeMillis();
      do {
       namesystem.readLock();
       try {
         num = namesystem.countNodes(block);
         namesystem.metaSave("TestDecommissionDataNodeAfterDeleteExcessReplica.meta");
       } finally {
         namesystem.readUnlock();
       }
       Thread.sleep(1000);
       LOG.info("live: " + num.liveReplicas() + "Decom: " + num.decommissionedReplicas());
      } while (num.liveReplicas() != 3 && 
          System.currentTimeMillis() - startTime < 30000);
      assertEquals("Live Replicas doesn't reach 3", num.liveReplicas(), 3);
    } finally {
      cluster.shutdown();
    }
  }
  
  private void waitForExcessReplicasToChange(
      FSNamesystem namesystem,
      Block block,
      int newReplicas) throws Exception
    {
      NumberReplicas num;
      long startChecking = System.currentTimeMillis();
      do {
        namesystem.readLock();
        try {
          num = namesystem.countNodes(block);
        } finally {
          namesystem.readUnlock();
        }
        Thread.sleep(100);
        if (System.currentTimeMillis() - startChecking > 30000) {
          fail("Timed out waiting for excess replicas to change");
        }

      } while (num.excessReplicas() != newReplicas);
    }
  
  private void addToExcludeFile(Configuration conf, DatanodeDescriptor[] dns) throws Exception {
    String excludeFN = conf.get("dfs.hosts.exclude", "");
    assertTrue(excludeFN.equals(HOST_FILE_PATH));
    File f = new File(excludeFN);
    if (f.exists()) {
      f.delete();
    }
    PrintWriter writer = new PrintWriter(new FileWriter(f, true));
    try {
      for (DatanodeDescriptor dn: dns) {
        writer.println(dn.getName());
      }
    } finally {
      writer.close();
    }
  }
}
