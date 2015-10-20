/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.isammoc.zooviewer.node;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Arrays;
import org.apache.zookeeper.data.Stat;

/**
 *
 * @author Admin
 */
public class ZVNodeImpl implements ZVNode {

    private final String path;
    private final String name;
    private boolean exists;
    private byte[] data;
    private Stat stat;
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    public ZVNodeImpl(String path) {
        this.path = path;
        this.name = "/".equals(path) ? "/" : path.substring(path.lastIndexOf("/") + 1);
        this.exists = false;
    }

    public ZVNodeImpl(String path, byte[] data) {
        this.path = path;
        this.name = "/".equals(path) ? "/" : path.substring(path.lastIndexOf("/") + 1);
        this.data = data == null ? null : Arrays.copyOf(data, data.length);
        this.exists = true;
    }

    @Override
    public String getPath() {
        return this.path;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public byte[] getData() {
        if (this.data == null) {
            return null;
        } else {
            return Arrays.copyOf(this.data, this.data.length);
        }
    }

    public void setData(byte[] data) {
        if (!Arrays.equals(this.data, data)) {
            byte[] old = this.data;
            this.data = data == null ? null : Arrays.copyOf(data, data.length);
            this.pcs.firePropertyChange(PROPERTY_DATA, old, data);
        }
    }

    @Override
    public boolean exists() {
        return this.exists;
    }

    public void setExists(boolean exists) {
        if (exists != this.exists) {
            this.exists = exists;
            this.pcs.firePropertyChange(PROPERTY_EXISTS, !this.exists, this.exists);
        }
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        this.pcs.addPropertyChangeListener(listener);
    }

    @Override
    public void addPropertyChangeListener(String propertyName, PropertyChangeListener listener) {
        this.pcs.addPropertyChangeListener(propertyName, listener);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        this.pcs.removePropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(String propertyName, PropertyChangeListener listener) {
        this.pcs.removePropertyChangeListener(propertyName, listener);
    }

    @Override
    public Stat getStat() {
        return this.stat == null ? null : copyStat(this.stat);
    }

    public void setStat(Stat stat) {
        if (this.stat == null ? stat != null : !this.stat.equals(stat)) {
            Stat old = this.stat;
            this.stat = stat == null ? null : copyStat(stat);
            this.pcs.firePropertyChange(PROPERTY_STAT, old, stat);
        }
    }

    private static Stat copyStat(Stat stat) {
        return new Stat(stat.getCzxid(), stat.getMzxid(), stat.getCtime(),
                stat.getMtime(), stat.getVersion(), stat.getCversion(),
                stat.getAversion(), stat.getEphemeralOwner(),
                stat.getDataLength(), stat.getNumChildren(), stat.getPzxid());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }
        ZVNodeImpl other = (ZVNodeImpl) obj;
        return this.path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return this.path.hashCode();
    }

    @Override
    public String toString() {
        return String.format("ZVNodeImpl[path='%s', " + this.exists + ", length='%d']", this.path, (this.data == null ? -1 : this.data.length));
    }
}
