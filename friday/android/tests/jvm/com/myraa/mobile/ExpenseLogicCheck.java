package com.myraa.mobile;

import java.util.*;

/** Checks the expense tracker's amount and category reading and its summaries on a plain JVM. */
public final class ExpenseLogicCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }

  public static void main(String[] args) {
    check(ExpenseLogic.amount("500 rupay petrol") == 500, "500 rupay");
    check(ExpenseLogic.amount("Rs 1,200 groceries") == 1200, "Rs 1,200");
    check(ExpenseLogic.amount("spent 1.5k on chai") == 1500, "1.5k");
    check(ExpenseLogic.amount("2 hazaar ka bijli bill") == 2000, "2 hazaar");
    check(ExpenseLogic.amount("3 lakh car repair") == 300000, "3 lakh");
    check(ExpenseLogic.amount("250.50 pkr") == 250.5, "decimal");
    check(ExpenseLogic.amount("meeting at 5 baje, paid 800 for lunch") == 800, "time word is not money: " + ExpenseLogic.amount("meeting at 5 baje, paid 800 for lunch"));
    check(ExpenseLogic.amount("no money here") == -1, "no amount");
    check(ExpenseLogic.amount("999999999999 rupees") == -1, "absurd amount refused");

    check(ExpenseLogic.category("", "500 rupay petrol").equals("transport"), "petrol is transport");
    check(ExpenseLogic.category("", "bijli ka bill 2 hazaar").equals("bills"), "bijli is bills");
    check(ExpenseLogic.category("", "dawai 300").equals("health"), "dawai is health");
    check(ExpenseLogic.category("khana", "x").equals("food"), "khana is food");
    check(ExpenseLogic.category("", "sadqa 1000").equals("charity"), "sadqa is charity");
    check(ExpenseLogic.category("", "random thing 40").equals("other"), "unknown is other");
    check(ExpenseLogic.category("weird", "biryani").equals("food"), "unknown category falls back to a guess");

    check(ExpenseLogic.note("500 rupay petrol pe kharch kiye").equals("petrol"), "note: " + ExpenseLogic.note("500 rupay petrol pe kharch kiye"));
    check(ExpenseLogic.note("Rs 1,200 groceries").equals("groceries"), "note: " + ExpenseLogic.note("Rs 1,200 groceries"));
    check(ExpenseLogic.note("expense: lunch with Ali 800").equals("lunch with Ali"), "note: " + ExpenseLogic.note("expense: lunch with Ali 800"));

    boolean threw = false; try { ExpenseLogic.check(500, "card 4111 1111 1111 1111"); } catch (IllegalArgumentException e) { threw = true; }
    check(threw, "card number refused");
    threw = false; try { ExpenseLogic.check(-1, "x"); } catch (IllegalArgumentException e) { threw = true; }
    check(threw, "missing amount refused");

    TimeZone z = TimeZone.getTimeZone("Asia/Karachi");
    Calendar c = Calendar.getInstance(z); c.set(2026, Calendar.NOVEMBER, 9, 15, 30, 0); c.set(Calendar.MILLISECOND, 0); long now = c.getTimeInMillis();
    Calendar d = Calendar.getInstance(z); d.setTimeInMillis(ExpenseLogic.since("today", now, z));
    check(d.get(Calendar.DAY_OF_MONTH) == 9 && d.get(Calendar.HOUR_OF_DAY) == 0, "today starts at midnight");
    d.setTimeInMillis(ExpenseLogic.since("month", now, z)); check(d.get(Calendar.DAY_OF_MONTH) == 1 && d.get(Calendar.MONTH) == Calendar.NOVEMBER, "month starts on the 1st");
    d.setTimeInMillis(ExpenseLogic.since("week", now, z)); check(d.get(Calendar.DAY_OF_MONTH) == 3, "week is the last 7 days");
    check(ExpenseLogic.since("all", now, z) == -1, "all means everything");
    check(ExpenseLogic.until("yesterday", now, z) == ExpenseLogic.since("today", now, z), "yesterday ends at today's start");

    Map<String, Double> totals = new LinkedHashMap<>(); totals.put("transport", 1800.0); totals.put("food", 2500.0);
    String s = ExpenseLogic.summary(totals, 3, "month");
    check(s.equals("Rs 4,300 this month in 3 expenses: food Rs 2,500, transport Rs 1,800."), "summary: " + s);
    check(ExpenseLogic.summary(new HashMap<>(), 0, "today").equals("No expenses recorded today."), "empty summary");
    check(ExpenseLogic.money(250.5).equals("Rs 250.50"), "money with decimals");

    if (failures > 0) { System.out.println(failures + " expense check(s) failed"); System.exit(1); }
    System.out.println("Expense checks passed");
  }
}
