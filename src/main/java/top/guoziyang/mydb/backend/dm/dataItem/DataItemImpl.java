package top.guoziyang.mydb.backend.dm.dataItem;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import top.guoziyang.mydb.backend.common.SubArray;
import top.guoziyang.mydb.backend.dm.DataManagerImpl;
import top.guoziyang.mydb.backend.dm.page.Page;

/**
 * dataItem 结构如下：
 * [ValidFlag] [DataSize] [Data]
 * ValidFlag 1字节，0为合法，1为非法
 * DataSize  2字节，标识Data的长度
 */
public class DataItemImpl implements DataItem {
    //数据偏移量起始位置
    static final int OF_VALID = 0;
    static final int OF_SIZE = 1;
    static final int OF_DATA = 3;

    private SubArray raw;//自定义数组片段类，根据偏移量访问数据，避免不必要的拷贝
    private byte[] oldRaw;//修改前的原始数据
    private Lock rLock;
    private Lock wLock;
    private DataManagerImpl dm;
    private long uid;//数据项唯一标识符，页面号+页内偏移量
    private Page pg;//页面类，数据项载体

    public DataItemImpl(SubArray raw, byte[] oldRaw, Page pg, long uid, DataManagerImpl dm) {
        this.raw = raw;
        this.oldRaw = oldRaw;
        ReadWriteLock lock = new ReentrantReadWriteLock();
        rLock = lock.readLock();
        wLock = lock.writeLock();
        this.dm = dm;
        this.uid = uid;
        this.pg = pg;
    }

    public boolean isValid() {
        return raw.buffer[raw.start+OF_VALID] == (byte)0;
    }

    @Override
    public SubArray data() {
        return new SubArray(raw.buffer, raw.start+OF_DATA, raw.end);
    }

    @Override
    public void before() {//事务修改前的准备工作
        wLock.lock();
        //标记页面为脏页
        pg.setDirty(true);
        //备份旧数据到oldRaw
        System.arraycopy(raw.buffer, raw.start, oldRaw, 0, oldRaw.length);
    }

    //撤销修改,回滚
    @Override
    public void unBefore() {
        System.arraycopy(oldRaw, 0, raw.buffer, raw.start, oldRaw.length);
        wLock.unlock();
    }
    //提交修改
    @Override
    public void after(long xid) {
        dm.logDataItem(xid, this);//记录redo日志
        wLock.unlock();
    }
//    释放对该数据项的引用
    @Override
    public void release() {
        dm.releaseDataItem(this);
    }

    @Override
    public void lock() {
        wLock.lock();
    }

    @Override
    public void unlock() {
        wLock.unlock();
    }

    @Override
    public void rLock() {
        rLock.lock();
    }

    @Override
    public void rUnLock() {
        rLock.unlock();
    }

    @Override
    public Page page() {
        return pg;
    }

    @Override
    public long getUid() {
        return uid;
    }

    @Override
    public byte[] getOldRaw() {
        return oldRaw;
    }

    @Override
    public SubArray getRaw() {
        return raw;
    }
    
}
