package top.guoziyang.mydb.backend.im;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import top.guoziyang.mydb.backend.common.SubArray;
import top.guoziyang.mydb.backend.dm.dataItem.DataItem;
import top.guoziyang.mydb.backend.tm.TransactionManagerImpl;
import top.guoziyang.mydb.backend.utils.Parser;

/**
 * Node结构如下：
 * [LeafFlag(1)][KeyNumber(2)][SiblingUid(8)]
 * [Son0][Key0][Son1][Key1]...[SonN][KeyN]
 */
public class Node {
    static final int IS_LEAF_OFFSET = 0;
    static final int NO_KEYS_OFFSET = IS_LEAF_OFFSET+1;
    static final int SIBLING_OFFSET = NO_KEYS_OFFSET+2;
    static final int NODE_HEADER_SIZE = SIBLING_OFFSET+8;  //头部一共11字节

    static final int BALANCE_NUMBER = 32;                 //平衡因子
    //数据部分大小（8+8）x(32x2+2)=1056字节
    static final int NODE_SIZE = NODE_HEADER_SIZE + (2*8)*(BALANCE_NUMBER*2+2);

    BPlusTree tree;
    DataItem dataItem;
    SubArray raw;
    long uid;

    static void setRawIsLeaf(SubArray raw, boolean isLeaf) {
        //叶子结点标记为1
        if(isLeaf) {
            raw.buffer[raw.start + IS_LEAF_OFFSET] = (byte)1;
        } else {
            raw.buffer[raw.start + IS_LEAF_OFFSET] = (byte)0;
        }
    }

    static boolean getRawIfLeaf(SubArray raw) {
        return raw.buffer[raw.start + IS_LEAF_OFFSET] == (byte)1;
    }

    //键数量管理
    static void setRawNoKeys(SubArray raw, int noKeys) {
        //将int转为2字节short存储
        System.arraycopy(Parser.short2Byte((short)noKeys), 0, raw.buffer, raw.start+NO_KEYS_OFFSET, 2);
    }

    static int getRawNoKeys(SubArray raw) {
        //转回 int
        return (int)Parser.parseShort(Arrays.copyOfRange(raw.buffer, raw.start+NO_KEYS_OFFSET, raw.start+NO_KEYS_OFFSET+2));
    }
    //兄弟节点指针
    static void setRawSibling(SubArray raw, long sibling) {
        System.arraycopy(Parser.long2Byte(sibling), 0, raw.buffer, raw.start+SIBLING_OFFSET, 8);
    }

    static long getRawSibling(SubArray raw) {
        return Parser.parseLong(Arrays.copyOfRange(raw.buffer, raw.start+SIBLING_OFFSET, raw.start+SIBLING_OFFSET+8));
    }

    static void setRawKthSon(SubArray raw, long uid, int kth) {
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2);
        System.arraycopy(Parser.long2Byte(uid), 0, raw.buffer, offset, 8);
    }

    static long getRawKthSon(SubArray raw, int kth) {
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2);
        return Parser.parseLong(Arrays.copyOfRange(raw.buffer, offset, offset+8));
    }

    static void setRawKthKey(SubArray raw, long key, int kth) {
        //获取第K个子节点位置，头部大小+kx16字节
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2)+8;
        System.arraycopy(Parser.long2Byte(key), 0, raw.buffer, offset, 8);
    }

    static long getRawKthKey(SubArray raw, int kth) {
        //获取第K个键的位置，头部大小+kx16字节+8字节
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2)+8;
        //解析键值
        return Parser.parseLong(Arrays.copyOfRange(raw.buffer, offset, offset+8));
    }

    static void copyRawFromKth(SubArray from, SubArray to, int kth) {
        int offset = from.start+NODE_HEADER_SIZE+kth*(8*2);
        System.arraycopy(from.buffer, offset, to.buffer, to.start+NODE_HEADER_SIZE, from.end-offset);
    }

    static void shiftRawKth(SubArray raw, int kth) {
        int begin = raw.start+NODE_HEADER_SIZE+(kth+1)*(8*2);
        int end = raw.start+NODE_SIZE-1;
        for(int i = end; i >= begin; i --) {
            raw.buffer[i] = raw.buffer[i-(8*2)];
        }
    }

    static byte[] newRootRaw(long left, long right, long key)  {
        //创建新的根节点（分裂时）
        SubArray raw = new SubArray(new byte[NODE_SIZE], 0, NODE_SIZE);

        setRawIsLeaf(raw, false);
        setRawNoKeys(raw, 2);
        setRawSibling(raw, 0);
        setRawKthSon(raw, left, 0);
        setRawKthKey(raw, key, 0);
        setRawKthSon(raw, right, 1);
        setRawKthKey(raw, Long.MAX_VALUE, 1);

        return raw.buffer;
    }

    static byte[] newNilRootRaw()  {
        SubArray raw = new SubArray(new byte[NODE_SIZE], 0, NODE_SIZE);

        setRawIsLeaf(raw, true);
        setRawNoKeys(raw, 0);
        setRawSibling(raw, 0);

        return raw.buffer;
    }

    static Node loadNode(BPlusTree bTree, long uid) throws Exception {
        DataItem di = bTree.dm.read(uid);
        assert di != null;
        Node n = new Node();
        n.tree = bTree;
        n.dataItem = di;
        n.raw = di.data();
        n.uid = uid;
        return n;
    }

    public void release() {
        dataItem.release();
    }

    public boolean isLeaf() {
        dataItem.rLock();
        try {
            return getRawIfLeaf(raw);
        } finally {
            dataItem.rUnLock();
        }
    }

    class SearchNextRes {
        long uid;// 要访问的子节点UID
        long siblingUid; // 兄弟节点UID
    }

    public SearchNextRes searchNext(long key) {//找下一个节点
        dataItem.rLock();
        try {
            SearchNextRes res = new SearchNextRes();
            //当前节点键数量
            int noKeys = getRawNoKeys(raw);
            for(int i = 0; i < noKeys; i ++) {
                //第i个键的值
                long ik = getRawKthKey(raw, i);
                if(key < ik) {
                    //第一个大于搜索键的值，返回对应子节点
                    res.uid = getRawKthSon(raw, i);
                    res.siblingUid = 0;//不需要访问兄弟节点
                    return res;
                }
            }
            //所有键都小于等于搜索键，则没有合适子节点，尝试兄弟节点
            res.uid = 0;
            res.siblingUid = getRawSibling(raw);
            return res;

        } finally {
            dataItem.rUnLock();
        }
    }

    class LeafSearchRangeRes {
        List<Long> uids;  // 匹配的数据记录UID列表
        long siblingUid;// 下一个兄弟叶子节点UID
    }
    //在叶子节点中执行范围查询，支持跨叶子节点的连续搜索
    public LeafSearchRangeRes leafSearchRange(long leftKey, long rightKey) {
        dataItem.rLock();
        try {
            //当前叶子结点键数量
            int noKeys = getRawNoKeys(raw);
            int kth = 0;
            // 1. 找到第一个 >= leftKey 的键
            while(kth < noKeys) {
                long ik = getRawKthKey(raw, kth);
                if(ik >= leftKey) {
                    break;
                }
                kth ++;
            }
            //收集指定的letf-rightKey区间内的键对应的数据记录UID
            List<Long> uids = new ArrayList<>();
            while(kth < noKeys) {
                long ik = getRawKthKey(raw, kth);
                if(ik <= rightKey) {
                    uids.add(getRawKthSon(raw, kth));
                    kth ++;
                } else {
                    break;
                }
            }
            //检查是否需要兄弟节点
            long siblingUid = 0;
            if(kth == noKeys) {
                //当前叶子结点搜索完毕而有可能还是满足ik<=rightKey的键，则需要访问兄弟节点
                siblingUid = getRawSibling(raw);
            }
            LeafSearchRangeRes res = new LeafSearchRangeRes();
            res.uids = uids;
            res.siblingUid = siblingUid;
            return res;
        } finally {
            dataItem.rUnLock();
        }
    }

    class InsertAndSplitRes {
        long siblingUid, newSon, newKey;
    }
    //节点插入与分裂
    public InsertAndSplitRes insertAndSplit(long uid, long key) throws Exception {
        boolean success = false;
        Exception err = null;
        InsertAndSplitRes res = new InsertAndSplitRes();

        dataItem.before();//开始事务
        try {
            //尝试插入数据
            success = insert(uid, key);
            if(!success) {
                //插入失败返回兄弟节点重试
                res.siblingUid = getRawSibling(raw);
                return res;
            }
            //节点是否需要分裂
            if(needSplit()) {
                try {
                    //执行分裂
                    SplitRes r = split();
                    res.newSon = r.newSon;
                    res.newKey = r.newKey;
                    return res;
                } catch(Exception e) {
                    err = e;
                    throw e;
                }
            } else {
                return res;
            }
        } finally {
            if(err == null && success) {
                dataItem.after(TransactionManagerImpl.SUPER_XID);
            } else {
                dataItem.unBefore();
            }
        }
    }

    private boolean insert(long uid, long key) {
        int noKeys = getRawNoKeys(raw);
        int kth = 0;
        // 1. 找到第一个 >= key 的键插入位置
        while(kth < noKeys) {
            long ik = getRawKthKey(raw, kth);
            if(ik < key) {
                kth ++;
            } else {
                break;
            }
        }
        //键数遍历完还未找到目标，且有兄弟节点，则返回失败，此时会尝试兄弟节点
        if(kth == noKeys && getRawSibling(raw) != 0) return false;
        //叶子结点插入
        if(getRawIfLeaf(raw)) {
            //移动数据腾出空间
            shiftRawKth(raw, kth);
            setRawKthKey(raw, key, kth);
            setRawKthSon(raw, uid, kth);
            setRawNoKeys(raw, noKeys+1);// 更新键数量
        } else {
            //内部节点插入
            long kk = getRawKthKey(raw, kth);
            setRawKthKey(raw, key, kth);// 更新当前键
            shiftRawKth(raw, kth+1);// 移动后续数据
            setRawKthKey(raw, kk, kth+1);// 恢复原键值
            setRawKthSon(raw, uid, kth+1);// 设置新子节点
            setRawNoKeys(raw, noKeys+1);
        }
        return true;
    }

    private boolean needSplit() {
        return BALANCE_NUMBER*2 == getRawNoKeys(raw);
    }

    class SplitRes {
        long newSon, newKey;
    }
    //分裂：节点超过了BALANCE_NUMBER*2
    private SplitRes split() throws Exception {
        //创建新节点
        SubArray nodeRaw = new SubArray(new byte[NODE_SIZE], 0, NODE_SIZE);
        //保持相同类型
        setRawIsLeaf(nodeRaw, getRawIfLeaf(raw));
        //新节点包含一半的数据
        setRawNoKeys(nodeRaw, BALANCE_NUMBER);
        //继承兄弟指针
        setRawSibling(nodeRaw, getRawSibling(raw));
        //复制一半数据到新节点
        copyRawFromKth(raw, nodeRaw, BALANCE_NUMBER);
        //持久化新节点
        long son = tree.dm.insert(TransactionManagerImpl.SUPER_XID, nodeRaw.buffer);
        //更新当前节点，保留一半的数据
        setRawNoKeys(raw, BALANCE_NUMBER);
        //设置兄弟指针指向新节点
        setRawSibling(raw, son);

        SplitRes res = new SplitRes();
        res.newSon = son;
        res.newKey = getRawKthKey(nodeRaw, 0);
        return res;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Is leaf: ").append(getRawIfLeaf(raw)).append("\n");
        int KeyNumber = getRawNoKeys(raw);
        sb.append("KeyNumber: ").append(KeyNumber).append("\n");
        sb.append("sibling: ").append(getRawSibling(raw)).append("\n");
        for(int i = 0; i < KeyNumber; i ++) {
            sb.append("son: ").append(getRawKthSon(raw, i)).append(", key: ").append(getRawKthKey(raw, i)).append("\n");
        }
        return sb.toString();
    }

}
