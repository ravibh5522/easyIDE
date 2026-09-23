# App-specific R8 rules. The default proguard-android-optimize.txt already
# keeps JNI native method names (Termux JNI.createSubprocess & co.),
# @JavascriptInterface methods (MermaidView) and enum values()/valueOf().
# kotlin-textmate-core ships its own consumer rules for the Gson-read grammar
# models and jcodings; org.json is part of the Android framework.

# JGit's NLS loads each TranslationBundle subclass (JGitText, DfsText, ...) by
# class name as a ResourceBundle and fills its public String fields by
# reflection, matching field name to property key. Renaming either turns
# every git error message into a bundle-loading failure.
-keep class * extends org.eclipse.jgit.nls.TranslationBundle {
    public <init>();
    public <fields>;
}

# Android has no JMX. JGit only touches javax.management / ManagementFactory
# when a `jmx.<Class>` user git config flag opts in (default false), so those
# call sites are unreachable on-device with or without R8.
-dontwarn java.lang.management.**
-dontwarn javax.management.**
