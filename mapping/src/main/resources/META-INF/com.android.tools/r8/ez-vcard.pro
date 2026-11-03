# R8 reads this from the module's jar when it shrinks the app; `:mapping:testOnR8` runs the mapping tests with it.
# ez-vcard (ezvcard.util.CaseClasses) finds a parameter's value, such as ImageType.JPEG for TYPE=JPEG, among the
# public static fields of its class by reflection, and makes a value it doesn't know with the class's constructor,
# also by reflection. Without this rule R8 drops the constants and inlines or rewrites the constructors, so a card
# with a photo fails to parse or loses its media type.
-keepclassmembers class * extends ezvcard.parameter.VCardParameter {
    public static final <fields>;
    <init>(...);
}
