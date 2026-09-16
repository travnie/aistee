# Keep custom R8 rules narrow. Add rules only for runtime-reflective entry points
# that are not already covered by library consumer rules or Android manifest roots.

# Glance brings in WorkManager, whose Room implementation is instantiated reflectively.
# Under R8 full mode the generated class can survive while its no-arg constructor is removed.
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
