package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream

class MediaCarver(private val context: Context) {
    fun analyze(uri: Uri, offset: Long, type: MediaType, sourceSize: Long): CarveResult {
        val raw = context.contentResolver.openInputStream(uri) ?: return bad()
        raw.use {
            val base = BufferedInputStream(it, 128 * 1024)
            if (!skipFully(base, offset)) return bad()
            val s = CountingInputStream(base)
            return when (type) {
                MediaType.JPEG -> jpeg(s)
                MediaType.PNG -> png(s)
                MediaType.GIF -> gif(s)
                MediaType.WEBP -> webp(s)
                MediaType.BMP -> bmp(s)
                MediaType.MP4, MediaType.MOV, MediaType.THREE_GP, MediaType.HEIC -> isoBmff(s, sourceSize - offset)
                else -> bad()
            }
        }
    }

    private fun jpeg(s: CountingInputStream): CarveResult {
        if (s.read() != 0xFF || s.read() != 0xD8) return bad()
        while (true) {
            var marker = s.read()
            while (marker == 0xFF) marker = s.read()
            if (marker < 0) return partial(s)
            if (marker == 0xD9) return valid(s, 99)
            if (marker == 0xDA) return if (skipJpegScan(s)) valid(s, 99) else partial(s)
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) continue
            val hi=s.read(); val lo=s.read(); if(hi<0||lo<0)return partial(s)
            val length=(hi shl 8) or lo
            if(length<2 || !skipFully(s,length-2L))return partial(s)
        }
    }

    private fun skipJpegScan(s: CountingInputStream): Boolean {
        var ff=false
        while(true){
            val b=s.read(); if(b<0)return false
            if(ff){
                when { b==0x00 -> ff=false; b in 0xD0..0xD7 -> ff=false; b==0xD9 -> return true; b==0xFF -> ff=true; else -> ff=false }
            } else if(b==0xFF) ff=true
        }
    }

    private fun png(s: CountingInputStream): CarveResult {
        val sig=ByteArray(8); if(!readExact(s,sig)||!sig.contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)))return bad()
        var chunks=0
        while(true){
            val len=readU32(s)?:return partial(s); if(len>256L*1024L*1024L)return bad()
            val type=ByteArray(4);if(!readExact(s,type))return partial(s)
            if(!skipFully(s,len+4))return partial(s)
            chunks++;if(type.contentEquals("IEND".toByteArray()))return valid(s,if(chunks>=3)99 else 90)
        }
    }

    private fun gif(s: CountingInputStream): CarveResult {
        val h=ByteArray(13);if(!readExact(s,h))return partial(s)
        val version=String(h,0,6,Charsets.US_ASCII);if(version!="GIF87a"&&version!="GIF89a")return bad()
        val packed=h[10].toInt() and 255
        if((packed and 0x80)!=0&&!skipFully(s,3L*(1L shl ((packed and 7)+1))))return partial(s)
        while(true){
            when(val block=s.read()){
                0x3B->return valid(s,98)
                0x21->{if(s.read()<0||!skipSubBlocks(s))return partial(s)}
                0x2C->{if(!skipFully(s,9))return partial(s);val p=s.read();if(p<0)return partial(s);if((p and 0x80)!=0&&!skipFully(s,3L*(1L shl ((p and 7)+1))))return partial(s);if(s.read()<0||!skipSubBlocks(s))return partial(s)}
                else->return bad()
            }
        }
    }

    private fun webp(s: CountingInputStream): CarveResult { val h=ByteArray(12);if(!readExact(s,h))return partial(s);if(!h.copyOfRange(0,4).contentEquals("RIFF".toByteArray())||!h.copyOfRange(8,12).contentEquals("WEBP".toByteArray()))return bad();val n=u32(h,4)+8;return if(n>=20)CarveResult(n,RecoveryQuality.VALID,97) else bad() }
    private fun bmp(s: CountingInputStream): CarveResult { val h=ByteArray(54);if(!readExact(s,h))return partial(s);if(h[0].toInt()!=0x42||h[1].toInt()!=0x4D)return bad();val n=u32(h,2);val dib=u32(h,14);val pixel=u32(h,10);return if(n>=54&&dib>=12&&pixel<n)CarveResult(n,RecoveryQuality.VALID,97) else bad() }

    private fun isoBmff(s: CountingInputStream, available: Long): CarveResult {
        var consumed=0L;var boxes=0;var ftyp=false;var mdat=false;var metadata=false
        while(consumed+8<=available){
            val h=ByteArray(8);if(!readExact(s,h))break
            var n=u32(h,0);var header=8L
            if(n==1L){n=readU64(s)?:return partial(s);header=16}else if(n==0L)n=available-consumed
            if(n<header||n>available-consumed)return partial(s)
            val type=String(h,4,4,Charsets.US_ASCII)
            if(type=="ftyp")ftyp=true;if(type=="mdat")mdat=true;if(type=="moov"||type=="meta")metadata=true
            if(!skipFully(s,n-header))return partial(s)
            consumed+=n;boxes++
            if(type=="moov")return CarveResult(consumed,RecoveryQuality.VALID,98)
        }
        return if(ftyp&&(metadata||mdat)&&boxes>0)CarveResult(consumed,RecoveryQuality.LIKELY_RECOVERABLE,88) else if(boxes>0)CarveResult(consumed,RecoveryQuality.PARTIAL_OR_CORRUPT,55) else bad()
    }

    private fun skipSubBlocks(s:InputStream):Boolean{while(true){val n=s.read();if(n<0)return false;if(n==0)return true;if(!skipFully(s,n.toLong()))return false}}
    private fun readU32(s:InputStream):Long?{val b=ByteArray(4);return if(readExact(s,b))u32(b,0)else null}
    private fun readU64(s:InputStream):Long?{val b=ByteArray(8);if(!readExact(s,b))return null;var v=0L;for(x in b)v=(v shl 8)or(x.toLong()and 255);return v}
    private fun u32(b:ByteArray,o:Int):Long=((b[o].toLong()and 255)shl 24)or((b[o+1].toLong()and 255)shl 16)or((b[o+2].toLong()and 255)shl 8)or(b[o+3].toLong()and 255)
    private fun readExact(s:InputStream,b:ByteArray):Boolean{var p=0;while(p<b.size){val n=s.read(b,p,b.size-p);if(n<0)return false;p+=n};return true}
    private fun skipFully(s:InputStream,n:Long):Boolean{var left=n;while(left>0){val k=s.skip(left);if(k>0){left-=k;continue};if(s.read()<0)return false;left--};return true}
    private fun valid(s:CountingInputStream,c:Int)=CarveResult(s.count,RecoveryQuality.VALID,c)
    private fun partial(s:CountingInputStream)=CarveResult(s.count,RecoveryQuality.PARTIAL_OR_CORRUPT,55)
    private fun bad()=CarveResult(0,RecoveryQuality.SIGNATURE_ONLY,20)
}

private class CountingInputStream(private val delegate:InputStream):InputStream(){
    var count=0L
    override fun read():Int{val x=delegate.read();if(x>=0)count++;return x}
    override fun read(b:ByteArray,o:Int,l:Int):Int{val n=delegate.read(b,o,l);if(n>0)count+=n;return n}
    override fun skip(n:Long):Long{val k=delegate.skip(n);count+=k;return k}
}

data class CarveResult(val length:Long,val quality:RecoveryQuality,val confidence:Int)
