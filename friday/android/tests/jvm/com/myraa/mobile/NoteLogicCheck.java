package com.myraa.mobile;

import java.util.*;

/** Checks Second Brain note kinds, tags, titles, the secret filter and typo-tolerant search on a plain JVM. */
public final class NoteLogicCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }

  public static void main(String[] args) {
    check(NoteLogic.TASK.equals(NoteLogic.kind("todo", "x")), "todo means task");
    check(NoteLogic.DECISION.equals(NoteLogic.kind("faisla", "x")), "faisla means decision");
    check(NoteLogic.IDEA.equals(NoteLogic.kind("", "idea: a voice diary")), "guessed idea");
    check(NoteLogic.TASK.equals(NoteLogic.kind("", "kal bank jana karna hai")), "Roman Urdu task");
    check(NoteLogic.TASK.equals(NoteLogic.kind("", "I need to send the invoice")), "English task");
    check(NoteLogic.DECISION.equals(NoteLogic.kind("", "We decided to stay on the Java stack")), "guessed decision");
    check(NoteLogic.NOTE.equals(NoteLogic.kind("", "Ali's shop opens at 10")), "plain note");
    check(NoteLogic.NOTE.equals(NoteLogic.kind("weird", "Ali's shop opens at 10")), "unknown kind falls back to a guess");

    List<String> tags = NoteLogic.tags("Send invoice #Work #haris-ai and #work again", "urgent, #Money");
    check(tags.equals(Arrays.asList("work", "haris-ai", "urgent", "money")), "tags: " + tags);
    check(NoteLogic.tags("email me at a#b.com", null).isEmpty(), "# inside a word is not a tag");
    check(NoteLogic.splitTags(NoteLogic.joinTags(tags)).equals(tags), "tags round trip");
    check(NoteLogic.tags("#a #b #c #d #e #f #g #h #i #j", null).size() == NoteLogic.MAX_TAGS, "tags are capped");

    check(NoteLogic.clean("  task:   send   the invoice ").equals("send the invoice"), "label and spaces removed");
    check(NoteLogic.title("Send the invoice. Then call Ali.").equals("Send the invoice"), "title is the first sentence");
    String longTitle = NoteLogic.title("This is a very long note about the plans for the new app that goes on and on and on");
    check(longTitle.length() <= 61 && longTitle.endsWith("…"), "long title cut at a word: " + longTitle);
    check(NoteLogic.title("buy milk #home").equals("buy milk"), "tags left out of the title");

    check(threw("my bank password is hunter2"), "passwords are refused");
    check(threw("card 4111 1111 1111 1111"), "card numbers are refused");
    check(threw("   "), "empty note refused");
    check(threw(new String(new char[NoteLogic.MAX_TEXT + 1]).replace('\0', 'a')), "too long refused");
    check(!threw("call the plumber"), "normal note accepted");

    check(NoteLogic.distance("invoice", "invoce", 1) == 1, "one letter missing is distance 1");
    check(NoteLogic.distance("invoice", "plumber", 1) > 1, "different words are far apart");
    check(NoteLogic.wordMatch("invoic", "invoice") == 2, "prefix match");
    check(NoteLogic.wordMatch("invoise", "invoice") == 1.5, "one typo match");
    check(NoteLogic.wordMatch("cat", "car") == 0, "short words need an exact match");

    List<String> none = Collections.emptyList();
    double exact = NoteLogic.score("invoice", "Send invoice", "Send invoice to Ali", none);
    double typo = NoteLogic.score("invoise", "Send invoice", "Send invoice to Ali", none);
    double miss = NoteLogic.score("plumber", "Send invoice", "Send invoice to Ali", none);
    check(exact > typo && typo > 0, "exact beats typo, typo still found");
    check(miss == 0, "unrelated search scores 0");
    check(NoteLogic.score("work", "Buy paper", "Buy paper", Arrays.asList("work")) > 0, "tags are searched");
    double both = NoteLogic.score("invoice ali", "Send invoice", "Send invoice to Ali", none);
    double one = NoteLogic.score("invoice ali", "Invoice template", "Invoice template invoice invoice", none);
    check(both > one, "matching all words beats matching one word");
    check(NoteLogic.score("", "x", "x", none) == 0, "empty search scores 0");

    if (failures > 0) { System.out.println(failures + " note check(s) failed"); System.exit(1); }
    System.out.println("NoteLogicCheck: all passed");
  }
  private static boolean threw(String text) { try { NoteLogic.check(text); return false; } catch (IllegalArgumentException e) { return true; } }
}
