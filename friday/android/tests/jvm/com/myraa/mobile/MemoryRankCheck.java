package com.myraa.mobile;

import java.util.*;

/** Checks memory categories, duplicate/contradiction keys, sensitivity, relevance and mood on a plain JVM. */
public final class MemoryRankCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }

  public static void main(String[] args) {
    check(MemoryRank.PROJECT.equals(MemoryRank.guessCategory("I am building the HARIS AI app")), "project category");
    check(MemoryRank.PREFERENCE.equals(MemoryRank.guessCategory("My favourite voice is Aoede")), "preference category");
    check(MemoryRank.PROFILE.equals(MemoryRank.guessCategory("My sister's name is Sara")), "profile category");
    check(MemoryRank.PREFERENCE.equals(MemoryRank.category("preferences", "anything")), "explicit category wins");
    check(MemoryRank.CONTEXT.equals(MemoryRank.category("summary", "x")), "summary is context");

    check(MemoryRank.subjectKey("My favourite colour is blue").equals(MemoryRank.subjectKey("my favourite colour is GREEN!")), "same subject, new value");
    check(MemoryRank.subjectKey("meri behen ka naam Sara hai").equals("meri behen ka naam"), "Roman Urdu subject: " + MemoryRank.subjectKey("meri behen ka naam Sara hai"));
    check(MemoryRank.subjectKey("I like tea").isEmpty(), "no subject without a copula");
    check(MemoryRank.similarity("I am building the HARIS AI app", "I'm building the Haris AI app") >= 0.75, "near duplicate");
    check(MemoryRank.similarity("I like tea", "My brother lives in Lahore") < 0.2, "different facts");

    check(MemoryRank.secret("my bank password is abc"), "password is secret");
    check(MemoryRank.secret("card 4111 1111 1111 1111"), "card number is secret");
    check(!MemoryRank.secret("I like the colour blue"), "plain fact is not secret");
    check(MemoryRank.sensitive("I take medicine for blood pressure"), "health is sensitive");
    check(MemoryRank.sensitive("My CNIC is in the drawer"), "ID is sensitive");
    check(!MemoryRank.sensitive("My favourite food is biryani"), "food is not sensitive");

    long now = System.currentTimeMillis();
    Set<String> q = MemoryRank.tokens("app ka kaam kahan tak pohcha?");
    double project = MemoryRank.score(q, "Working on the FRIDAY app, voice is done", MemoryRank.PROJECT, now - 86_400_000L * 3, 0, now);
    double food = MemoryRank.score(q, "Favourite food is biryani", MemoryRank.PREFERENCE, now, 0, now);
    check(project > food, "topic match ranks first (" + project + " > " + food + ")");

    check(Persona.read("aaj mood off hai") == Persona.Mood.SAD, "mood off -> sad");
    check(Persona.read("Tum pagal ho 😂") == Persona.Mood.PLAYFUL, "teasing -> playful");
    check(Persona.read("phir se nahi ho raha") == Persona.Mood.FRUSTRATED, "repeat failure -> frustrated");
    check(Persona.read("kal exam hai, tension ho rahi hai") == Persona.Mood.WORRIED, "exam tension -> worried");
    check(Persona.read("wow finally ho gaya!!") == Persona.Mood.EXCITED, "excited");
    check(Persona.read("Kya kar rahi ho?") == Persona.Mood.CALM, "casual -> calm");
    check(Persona.core().contains("not a human"), "persona is honest about being an AI");

    if (failures > 0) { System.out.println(failures + " memory/persona check(s) failed"); System.exit(1); }
    System.out.println("All memory and persona checks passed.");
  }
}
