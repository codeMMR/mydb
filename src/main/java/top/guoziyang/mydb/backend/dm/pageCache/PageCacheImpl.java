package top.guoziyang.mydb.backend.dm.pageCache;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.guoziyang.mydb.backend.common.AbstractCache;
import top.guoziyang.mydb.backend.dm.page.Page;
import top.guoziyang.mydb.backend.dm.page.PageImpl;
import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.common.Error;

public class PageCacheImpl extends AbstractCache<Page> implements PageCache {//继承抽象类缓存和接口页面缓存
    //最小内存页数限制
    private static final int MEM_MIN_LIM = 10;
    //数据库文件后缀名
    public static final String DB_SUFFIX = ".db";
    //随机访问文件对象，用于底层文件I/O操作
    private RandomAccessFile file;
    //文件通道，提供NIO方式的高效文件访问
    private FileChannel fc;
    //文件锁
    private Lock fileLock;
    //当前数据库文件中的总页数
    private AtomicInteger pageNumbers;

    PageCacheImpl(RandomAccessFile file, FileChannel fileChannel, int maxResource) {
        //调用父类构造器初始化最大缓存数量
        super(maxResource);
        if(maxResource < MEM_MIN_LIM) {
            Panic.panic(Error.MemTooSmallException);
        }
        long length = 0;
        try {
            length = file.length();
        } catch (IOException e) {
            Panic.panic(e);
        }
        this.file = file;
        this.fc = fileChannel;
        this.fileLock = new ReentrantLock();
        this.pageNumbers = new AtomicInteger((int)length / PAGE_SIZE);
    }

    public int newPage(byte[] initData) {      //创建页面
        int pgno = pageNumbers.incrementAndGet();
        Page pg = new PageImpl(pgno, initData, null);
        flush(pg);       //立即写入磁盘
        return pgno;
    }

    public Page getPage(int pgno) throws Exception {
        return get((long)pgno);
    }

    /**
     * 根据pageNumber从数据库文件中读取页数据，并包裹成Page
     */
    @Override
    protected Page getForCache(long key) throws Exception {
        int pgno = (int)key;
        long offset = PageCacheImpl.pageOffset(pgno);

        ByteBuffer buf = ByteBuffer.allocate(PAGE_SIZE);
        fileLock.lock();
        try {
            fc.position(offset);
            fc.read(buf); //读取页面数据到缓冲区
        } catch(IOException e) {
            Panic.panic(e);
        }
        fileLock.unlock();
        return new PageImpl(pgno, buf.array(), this); //创建页面并关联当前缓存
    }

    @Override
    protected void releaseForCache(Page pg) {  //释放缓存
        if(pg.isDirty()) {
            flush(pg);
            pg.setDirty(false);
        }
    }

    public void release(Page page) {
        release((long)page.getPageNumber());
    }

    public void flushPage(Page pg) {
        flush(pg);
    }

    private void flush(Page pg) {  //页面刷盘
        int pgno = pg.getPageNumber();
        long offset = pageOffset(pgno);

        fileLock.lock();
        try {
            ByteBuffer buf = ByteBuffer.wrap(pg.getData());//包装页面数据为字节缓存
            fc.position(offset);
            fc.write(buf);
            fc.force(false); // 强制刷到磁盘（不包含元数据）
        } catch(IOException e) {
            Panic.panic(e);
        } finally {
            fileLock.unlock();
        }
    }

    public void truncateByBgno(int maxPgno) {  //文件截断
        //重新计算文件大小
        long size = pageOffset(maxPgno + 1);
        try {
            file.setLength(size);   //截断文件
        } catch (IOException e) {
            Panic.panic(e);
        }
        pageNumbers.set(maxPgno);  //原子操作更新页面
    }

    @Override
    public void close() {
        super.close();   //调用父类关闭，触发所有脏页刷盘
        try {
            fc.close();
            file.close();
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    public int getPageNumber() {
        return pageNumbers.intValue();
    }

    private static long pageOffset(int pgno) {
        return (pgno-1) * PAGE_SIZE;   //页面偏移量计算，由于页号从1开始而内存序列从0所以减1

    }
    
}
