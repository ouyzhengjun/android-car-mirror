package com.example.carmirror.scrcpy;

import java.io.IOException;
import java.util.Arrays;

/** Splits Annex B AVC parameter sets into Android's documented csd-0 SPS / csd-1 PPS. */
final class AvcConfig {
  /** Parameter sets are tiny in normal H.264 streams. Bound retained data even for hostile peers. */
  static final int MAX_CONFIG_BYTES=256*1024;
  final byte[] sps, pps;
  private AvcConfig(byte[] sps, byte[] pps) { this.sps=sps; this.pps=pps; }
  static AvcConfig parse(byte[] data) throws IOException {
    Accumulator accumulator=new Accumulator();
    accumulator.accept(data);
    AvcConfig complete=accumulator.complete();
    if(complete==null)throw new IOException("AVC config must contain SPS and PPS");
    return complete;
  }

  /** Receives one or more codec-config packets for a single scrcpy video session. */
  static final class Accumulator {
    private byte[] sps,pps;

    /** Returns true if the effective parameter set changed and a codec must be recreated. */
    boolean accept(byte[] data) throws IOException {
      if(data==null||data.length==0||data.length>MAX_CONFIG_BYTES)
        throw new IOException("Invalid AVC config size");
      byte[] packetSps=null,packetPps=null;
      int offset=0;
      while(offset<data.length) {
        int startCode=prefix(data,offset);
        if(startCode==0)throw new IOException("Invalid AVC Annex B configuration");
        int start=offset+startCode,end=start;
        while(end<data.length&&prefix(data,end)==0)end++;
        if(start==end)throw new IOException("Empty AVC parameter set");
        int type=data[start]&31;
        if(type==7||type==8){
          byte[] nal=new byte[end-start+4];
          nal[3]=1;
          System.arraycopy(data,start,nal,4,end-start);
          if(type==7)packetSps=nal;else packetPps=nal;
        }
        offset=end;
      }
      if(packetSps==null&&packetPps==null)throw new IOException("AVC config lacks parameter sets");

      boolean wasComplete=sps!=null&&pps!=null;
      byte[] nextSps=packetSps!=null?packetSps:sps;
      byte[] nextPps=packetPps!=null?packetPps:pps;
      // A changed parameter set from an active codec must be paired with a fresh peer.
      if(wasComplete&&packetSps!=null&&packetPps==null&&!Arrays.equals(sps,packetSps))nextPps=null;
      if(wasComplete&&packetPps!=null&&packetSps==null&&!Arrays.equals(pps,packetPps))nextSps=null;
      long retained=(nextSps==null?0:nextSps.length)+(nextPps==null?0:nextPps.length);
      if(retained>MAX_CONFIG_BYTES)throw new IOException("AVC parameter sets exceed limit");
      boolean changed=!Arrays.equals(sps,nextSps)||!Arrays.equals(pps,nextPps);
      sps=nextSps;pps=nextPps;
      return changed;
    }
    AvcConfig complete(){return sps==null||pps==null?null:new AvcConfig(sps,pps);}
    void reset(){sps=null;pps=null;}
  }
  private static int prefix(byte[] b,int p){
    if(p+2>=b.length||b[p]!=0||b[p+1]!=0)return 0;
    if(b[p+2]==1)return 3;
    return p+3<b.length&&b[p+2]==0&&b[p+3]==1?4:0;
  }
}
