# Room and Compose ship their own consumer rules.

# Glance creates widget action callbacks by reflection.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
