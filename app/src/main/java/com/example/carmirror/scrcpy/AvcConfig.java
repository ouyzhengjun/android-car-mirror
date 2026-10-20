package com.example.carmirror.scrcpy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Splits Annex B AVC parameter sets into Android's documented csd-0 SPS / csd-1 PPS. */
final class AvcConfig {
  final byte[] sps, pps;
  private AvcConfig(byte[] sps, byte[] pps) { this.sps=sps; this.pps=pps; }
  static AvcConfig parse(byte[] data) throws IOException {
    ByteArrayOutputStream sps=new ByteArrayOutputStream(), pps=new ByteArrayOutputStream();
    int offset=0;
    while(offset<data.length) {
      int prefix=prefix(data,offset);
      if(prefix==0)throw new IOException("Invalid AVC Annex B configuration");
      int start=offset+prefix, end=start;
      while(end<data.length && prefix(data,end)==0)end++;
      if(start==end)throw new IOException("Empty AVC parameter set");
      int type=data[start]&31;
      ByteArrayOutputStream target=type==7?sps:type==8?pps:null;
      if(target!=null){target.write(new byte[]{0,0,0,1},0,4);target.write(data,start,end-start);}
      offset=end;
    }
    if(sps.size()==0||pps.size()==0)throw new IOException("AVC config must contain SPS and PPS");
    return new AvcConfig(sps.toByteArray(),pps.toByteArray());
  }
  private static int prefix(byte[] b,int p){
    if(p+2>=b.length||b[p]!=0||b[p+1]!=0)return 0;
    if(b[p+2]==1)return 3;
    return p+3<b.length&&b[p+2]==0&&b[p+3]==1?4:0;
  }
}
