package com.rob.speseoffline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ExpenseDbHelper(context: Context) :
    SQLiteOpenHelper(context, "spese_offline.db", null, 3) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createV1(db)
        createV2(db)
        createV3(db)
    }

    private fun createV1(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_cents INTEGER NOT NULL,
                category_id INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                date_iso TEXT NOT NULL,
                FOREIGN KEY(category_id) REFERENCES categories(id)
            )
        """.trimIndent())

        listOf("Alimentari","Casa","Trasporti","Ristoranti","Salute","Tempo libero","Shopping","Bollette","Altro")
            .forEach { name ->
                db.insert("categories", null, ContentValues().apply { put("name", name) })
            }

        db.execSQL("CREATE INDEX idx_expenses_date ON expenses(date_iso)")
        db.execSQL("CREATE INDEX idx_expenses_category ON expenses(category_id)")
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS monthly_budgets (
                year_month TEXT PRIMARY KEY,
                amount_cents INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS category_budgets (
                year_month TEXT NOT NULL,
                category_id INTEGER NOT NULL,
                amount_cents INTEGER NOT NULL,
                PRIMARY KEY(year_month, category_id),
                FOREIGN KEY(category_id) REFERENCES categories(id)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS recurring_expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_cents INTEGER NOT NULL,
                category_id INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                day_of_month INTEGER NOT NULL,
                active INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(category_id) REFERENCES categories(id)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS recurring_instances (
                recurring_id INTEGER NOT NULL,
                year_month TEXT NOT NULL,
                expense_id INTEGER NOT NULL,
                PRIMARY KEY(recurring_id, year_month),
                FOREIGN KEY(recurring_id) REFERENCES recurring_expenses(id) ON DELETE CASCADE,
                FOREIGN KEY(expense_id) REFERENCES expenses(id) ON DELETE CASCADE
            )
        """.trimIndent())
    }

    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT NOT NULL DEFAULT '•'")
        db.execSQL("ALTER TABLE categories ADD COLUMN color_argb INTEGER NOT NULL DEFAULT -10785537")
        db.execSQL("ALTER TABLE expenses ADD COLUMN transaction_type TEXT NOT NULL DEFAULT 'EXPENSE'")
        db.execSQL("ALTER TABLE recurring_expenses ADD COLUMN transaction_type TEXT NOT NULL DEFAULT 'EXPENSE'")

        val presets = listOf(
            Triple("Alimentari","🛒",0xFF5E9C76L),
            Triple("Casa","🏠",0xFF7B61AEL),
            Triple("Trasporti","🚗",0xFF3D7EABL),
            Triple("Ristoranti","🍽",0xFFC47B4CL),
            Triple("Salute","❤",0xFFB95C70L),
            Triple("Tempo libero","🎟",0xFF6D7F9EL),
            Triple("Shopping","🛍",0xFFB26B9DL),
            Triple("Bollette","💡",0xFFB48A44L),
            Triple("Altro","●",0xFF757575L)
        )
        presets.forEach { (name, icon, color) ->
            db.update("categories", ContentValues().apply {
                put("icon", icon); put("color_argb", color)
            }, "name = ?", arrayOf(name))
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) createV3(db)
    }
}
