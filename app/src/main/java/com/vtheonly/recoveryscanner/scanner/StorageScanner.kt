package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class StorageScanner(private val context: Context) {
    suspend fun scanTree(treeUri: Uri,onProgress:suspend(ScannedProgress)->Unit):List<ScanResult>=withContext(Dispatchers.IO){val root=DocumentFile.fromTreeUri(context,treeUri)?:return@withContext emptyList();val results=mutableListOf<ScanResult>();var scanned=0L;var bytes=0L;suspend fun visit(node:DocumentFile){coroutineContext.ensureActive();if(node.isDirectory){node.listFiles().forEach{visit(it)};return};if(!node.isFile)return;val size=node.length().coerceAtLeast(0L);val name=node.name?:"unknown";context.contentResolver.openInputStream(node.uri)?.use{input->{val d=SignatureRegistry.detect(input,name);if(d!=null){val ext=extension(name);results+=ScanResult(name,node.uri.toString(),d.type,d.extension,ext,size,d.confidence,ext.isNotEmpty()&&ext!=d.extension,d.offset>0,d.offset)}}};scanned++;bytes+=size;if(scanned%20L==0L)onProgress(ScannedProgress(scanned,bytes))};visit(root);onProgress(ScannedProgress(scanned,bytes,true));results}
    suspend fun scanMediaStore(onProgress:suspend(ScannedProgress)->Unit):List<ScanResult>=withContext(Dispatchers.IO){val results=mutableListOf<ScanResult>();val projection=arrayOf(android.provider.MediaStore.Files.FileColumns._ID,android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,android.provider.MediaStore.Files.FileColumns.SIZE);val collection=android.provider.MediaStore.Files.getContentUri("external");context.contentResolver.query(collection,projection,null,null,null)?.use{cursor->{val idCol=cursor.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns._ID);val nameCol=cursor.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME);val sizeCol=cursor.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.SIZE);var checked=0L;while(cursor.moveToNext()){coroutineContext.ensureActive();val id=cursor.getLong(idCol);val name=cursor.getString(nameCol)?:"unknown";val uri=Uri.withAppendedPath(collection,id.toString());context.contentResolver.openInputStream(uri)?.use{input->{val d=SignatureRegistry.detect(input,name);if(d!=null){val ext=extension(name);results+=ScanResult(name,uri.toString(),d.type,d.extension,ext,cursor.getLong(sizeCol),d.confidence,ext.isNotEmpty()&&ext!=d.extension,d.offset>0,d.offset)}}};checked++;if(checked%20L==0L)onProgress(ScannedProgress(checked,results.sumOf{it.sizeBytes}))}}};onProgress(ScannedProgress(results.size.toLong(),results.sumOf{it.sizeBytes},true));results}
    private fun extension(name:String)=name.substringAfterLast('.',"").lowercase()
}
data class ScannedProgress(val files:Long,val bytes:Long,val complete:Boolean=false)
