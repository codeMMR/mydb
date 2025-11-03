package top.guoziyang.mydb.backend.common;

public class SubArray {
    public byte[] buffer;
    public int start;
    public int end;

    public SubArray(byte[] raw, int start, int end) {
        this.buffer = raw;
        this.start = start;
        this.end = end;
    }
}
