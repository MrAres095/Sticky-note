package com.mrares095.stickynote
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
class NoteWidgetProvider: AppWidgetProvider(){
 override fun onUpdate(c:Context,m:AppWidgetManager,ids:IntArray){ids.forEach{update(c,m,it)}}
 companion object{fun update(c:Context,m:AppWidgetManager,id:Int){
  val p=c.getSharedPreferences("sticky_note_data",0); val raw=p.getString("notes",null)
  var title="Sticky & Note"; var text="Nema spremljenih bilješki."
  try{if(!raw.isNullOrBlank()){val a=org.json.JSONArray(raw);if(a.length()>0){val n=a.getJSONObject(0);title=n.optString("title","Bez naslova");text=n.optString("text","")}}}catch(_:Exception){}
  val v=RemoteViews(c.packageName,R.layout.widget_note);v.setTextViewText(R.id.widget_title,title);v.setTextViewText(R.id.widget_text,text);m.updateAppWidget(id,v)
 }}
}