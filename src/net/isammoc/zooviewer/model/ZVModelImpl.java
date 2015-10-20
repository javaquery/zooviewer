/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.isammoc.zooviewer.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.event.EventListenerList;
import net.isammoc.zooviewer.node.ZVNode;
import net.isammoc.zooviewer.node.ZVNodeImpl;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import static org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.Stat;

/**
 *
 * @author Admin
 */
public class ZVModelImpl implements ZVModel {

    private final ZkWatcher objZkWatcher;
    private final ZooKeeper objZooKeeper;

    private final Map<String, ZVNodeImpl> nodes = new HashMap<String, ZVNodeImpl>();
    private final Map<ZVNodeImpl, List<ZVNodeImpl>> children = new HashMap<ZVNodeImpl, List<ZVNodeImpl>>();
    protected final EventListenerList eventListenerList = new EventListenerList();
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor();
    private String connectionString;

    public ZVModelImpl(String connectionString) throws IOException {
        this.objZkWatcher = new ZkWatcher();
        this.objZooKeeper = new ZooKeeper(connectionString, 3000, this.objZkWatcher);
        this.connectionString = connectionString;

        synchronized (objZkWatcher.lock) {
            while (objZkWatcher.dead) {
                try {
//                    System.out.println("[" + Thread.currentThread() + "Awaiting lock notification");
                    objZkWatcher.lock.wait();

                    Object[] param = new Object[2];
                    param[0] = Thread.currentThread();
                    param[1] = objZkWatcher.dead;

//                    System.out.println("[" + Thread.currentThread() + "Lock notification, watcher.dead = " + objZkWatcher.dead);
                } catch (InterruptedException ex) {
                   ex.printStackTrace();
                }
            }
        }
        populateRoot();
    }

    private final class ZkWatcher implements Watcher {

        private final Object lock = new Object();
        private volatile boolean dead = true;

        @Override
        public void process(WatchedEvent event) {
            Object[] param = new Object[2];
            param[0] = Thread.currentThread();
            param[1] = objZkWatcher.dead;

            switch (event.getType()) {
                case None:
                    switch (event.getState()) {
                        case Disconnected:
                        case Expired:
                            synchronized (lock) {
                                dead = true;
                                lock.notifyAll();
                            }
                            break;
                        case SyncConnected:
                            synchronized (lock) {
                                dead = false;
                                lock.notifyAll();
                            }
                            break;
                    }
                    objZooKeeper.register(this);
                    break;
                case NodeCreated:
                    // FLE+
                    // nodeDataChanged(event.getPath());
                    break;
                case NodeChildrenChanged:
                    populateChildren(event.getPath());
                    break;
                case NodeDeleted:
                    nodeDeleted(event.getPath());
                    break;
                case NodeDataChanged:
                    nodeDataChanged(event.getPath());
                    break;
            }
        }
    }

    private synchronized void populateRoot() {
        if (nodes.get("/") == null) {
            Stat stat = new Stat();
            ZVNodeImpl root;
            try {
                root = new ZVNodeImpl("/", objZooKeeper.getData("/", objZkWatcher, stat));
                root.setStat(stat);
                nodes.put("/", root);
                children.put(root, new ArrayList<ZVNodeImpl>());
                fireNodeCreated(root);
                populateChildren("/");
            } catch (KeeperException ex) {
                Logger.getLogger(ZVModelImpl.class.getName()).log(Level.SEVERE, null, ex);
            } catch (InterruptedException ex) {
                Logger.getLogger(ZVModelImpl.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    protected void fireNodeCreated(ZVNode newNode) {
        // Guaranteed to return a non-null array
        Object[] listeners = eventListenerList.getListenerList();
        // Process the listeners last to first, notifying
        // those that are interested in this event
        for (int i = listeners.length - 2; i >= 0; i -= 2) {
            if (listeners[i] == ZVModelListener.class) {
                ((ZVModelListener) listeners[i + 1]).nodeCreated(newNode);
            }
        }
    }

    private synchronized void populateChildren(String path) {
        ChildrenCallback cb = new ChildrenCallback() {

            @Override
            public void processResult(int rc, String path, Object ctx,
                    List<String> childrenNames) {
                ZVNodeImpl parent = nodes.get(path);
                Stat stat = new Stat();
                try {
                    parent.setStat(objZooKeeper.exists(path, false));
                } catch (Exception ignore) {
                    ignore.printStackTrace();
                }
                for (String childName : childrenNames) {
                    try {
                        String childPath = getFullPath(path, childName);
                        ZVNodeImpl child = nodes.get(childPath);
                        if (child != null) {
                            if (!child.exists()) {
                                child.setData(objZooKeeper.getData(childPath, objZkWatcher, stat));
                                child.setStat(stat);
                                child.setExists(true);
                                children.put(child, new ArrayList<ZVNodeImpl>());
                                children.get(parent).add(child);
                                fireNodeCreated(child);
                                populateChildren(childPath);
                            }
                        } else {
                            child = new ZVNodeImpl(childPath, objZooKeeper.getData(
                                    childPath, objZkWatcher, stat));
                            child.setStat(stat);
                            nodes.put(childPath, child);
                            children.put(child, new ArrayList<ZVNodeImpl>());
                            children.get(parent).add(child);
                            fireNodeCreated(child);
                            populateChildren(childPath);
                        }
                    } catch (Exception ignore) {
                        ignore.printStackTrace();
                    }
                }
            }
        };
        objZooKeeper.getChildren(path, objZkWatcher, cb, null);
    }

    /**
     * Called when a node has been deleted in the ZooKeeper model.
     *
     * @param path the node path
     */
    private synchronized void nodeDeleted(String path) {
        ZVNodeImpl oldNode = nodes.get(path);
        if (oldNode != null) {
            oldNode.setExists(false);
            oldNode.setStat(null);
            ZVNodeImpl parent = nodes.get(getParent(path));
            int oldIndex = children.get(parent).indexOf(oldNode);
            children.get(parent).remove(oldNode);
            fireNodeDeleted(oldNode, oldIndex);
        }
    }

    protected void fireNodeDeleted(ZVNode oldNode, int oldIndex) {
        // Guaranteed to return a non-null array
        Object[] listeners = eventListenerList.getListenerList();
        // Process the listeners last to first, notifying
        // those that are interested in this event
        for (int i = listeners.length - 2; i >= 0; i -= 2) {
            if (listeners[i] == ZVModelListener.class) {
                ((ZVModelListener) listeners[i + 1]).nodeDeleted(oldNode,
                        oldIndex);
            }
        }
    }

    /**
     * Called when a node has been updated in the ZooKeeper model.
     *
     * @param path the node path
     */
    private synchronized void nodeDataChanged(String path) {
        ZVNodeImpl node = nodes.get(path);
        try {
            Stat stat = new Stat();
            node.setData(objZooKeeper.getData(path, objZkWatcher, stat));
            node.setStat(stat);
            fireNodeDataChanged(node);
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void fireNodeDataChanged(ZVNode node) {
        // Guaranteed to return a non-null array
        Object[] listeners = eventListenerList.getListenerList();
        //Process the listeners last to first, notifying
        // those that are interested in this event
        for (int i = listeners.length - 2; i >= 0; i -= 2) {
            if (listeners[i] == ZVModelListener.class) {
                ((ZVModelListener) listeners[i + 1]).nodeDataChanged(node);
            }
        }
    }

    /**
     * @param path
     * @return
     */
    private String getParent(String path) {
        if ("/".equals(path)) {
            return null;
        } else {
            int lastIndex = path.lastIndexOf("/");
            if (lastIndex > 0) {
                return path.substring(0, lastIndex);
            } else {
                return "/";
            }
        }
    }

    @Override
    public void addModelListener(ZVModelListener listener) {
        eventListenerList.add(ZVModelListener.class, listener);
    }

    @Override
    public void removeModelListener(ZVModelListener listener) {
        eventListenerList.remove(ZVModelListener.class, listener);
    }

    @Override
    public void addNode(String path, byte[] data) {
        if ((nodes.get(path) != null) && nodes.get(path).exists()) {
            throw new IllegalStateException("Node '" + path + "' already exists");
        }

        if ((nodes.get(getParent(path)) == null)
                || !nodes.get(getParent(path)).exists()) {
            throw new IllegalArgumentException("Node '" + path + "' can't be created. Its parent node doesn't exist");
        }

        try {
            objZooKeeper.create(path, data, OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void updateData(String path, byte[] data) {
        try {
            Stat stat = objZooKeeper.setData(path, data, -1);
            nodes.get(path).setStat(stat);
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void deleteNode(ZVNode node) {

        String path = node.getPath();
        PathUtils.validatePath(path);
        try {
            // Checks if the node has children
            List<String> childNodes = objZooKeeper.getChildren(path, false);
            if (childNodes != null && childNodes.size() > 0) {
                // if the node has children, delete them recursively
                for (Iterator<String> iterator = childNodes.iterator(); iterator
                        .hasNext();) {
                    String nodeName = (String) iterator.next();
                    String childPath = path + (path.endsWith("/") ? "" : "/")
                            + nodeName;
                    deleteNode(getNode(childPath));
                }
            }
            // finally, delete the node itself
//            Stat stat = objZooKeeper.exists(path, false);
            objZooKeeper.delete(path, -1);
//            Stat stat2 = objZooKeeper.exists(path, false);
        } catch (KeeperException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    @Override
    public void deleteNodes(ZVNode[] nodes) {
        for (int i = 0; i < nodes.length; i++) {
            deleteNode(nodes[i]);
        }
    }

    @Override
    public ZVNode getNode(String path) {
        return nodes.get(path);
    }

    @Override
    public ZVNode getParent(ZVNode node) {
        return getNode(getParent(node.getPath()));
    }

    @Override
    public List<ZVNode> getChildren(ZVNode parent) {
        List<ZVNode> nodes = new ArrayList<ZVNode>();
        for (ZVNode node : children.get(parent)) {
            if (node.exists()) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    @Override
    public String getFullPath(String parentPath, String childName) {
        return ("/".equals(parentPath) ? "/" : (parentPath + "/")) + childName;
    }

    @Override
    public void close() throws InterruptedException {

        System.out.println("Closing ZooKeeper client...");
        objZooKeeper.close();
        synchronized (objZkWatcher.lock) {
            objZkWatcher.dead = true;
            objZkWatcher.lock.notifyAll();
        }
        System.out.println("Shutting down watcher...");
        watcherExecutor.shutdown();
        System.out.println("Removing listeners...");
        ZVModelListener[] listeners = eventListenerList
                .getListeners(ZVModelListener.class);
        for (int i = 0; i < listeners.length; i++) {
            eventListenerList.remove(ZVModelListener.class, listeners[i]);
        }

        System.out.println("Resetting models...");
        nodes.clear();
        children.clear();
        System.out.println("Close done.");
    }

    @Override
    public String connectionString() {
        return this.connectionString;
    }    
}
