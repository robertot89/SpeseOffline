package com.rob.speseoffline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

class ExpenseRepository(context: Context) {
    private val helper = ExpenseDbHelper(context.applicationContext)

    fun categories(): List<Category> {
        val out = mutableListOf<Category>()
        helper.readableDatabase.rawQuery(
            "SELECT id,name,icon,color_argb FROM categories ORDER BY name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) out += Category(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))
        }
        return out
    }

    fun addCategory(name: String, icon: String = "●", colorArgb: Long = 0xFF5B6CFF): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return false
        return try {
            helper.writableDatabase.insertOrThrow("categories", null, ContentValues().apply {
                put("name", clean); put("icon", icon.ifBlank { "●" }); put("color_argb", colorArgb)
            })
            true
        } catch (_: Exception) { false }
    }

    fun updateCategory(id: Long, name: String, icon: String, colorArgb: Long): Boolean =
        try {
            helper.writableDatabase.update("categories", ContentValues().apply {
                put("name", name.trim()); put("icon", icon.ifBlank { "●" }); put("color_argb", colorArgb)
            }, "id = ?", arrayOf(id.toString())) > 0
        } catch (_: Exception) { false }

    fun deleteCategory(id: Long): Boolean {
        val db = helper.writableDatabase
        val used = db.rawQuery(
            """SELECT (
                (SELECT COUNT(*) FROM expenses WHERE category_id=?) +
                (SELECT COUNT(*) FROM recurring_expenses WHERE category_id=?)
            )""".trimIndent(), arrayOf(id.toString(), id.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        if (used > 0) return false
        db.delete("category_budgets", "category_id=?", arrayOf(id.toString()))
        return db.delete("categories", "id=?", arrayOf(id.toString())) > 0
    }

    fun addTransaction(amountCents: Long, categoryId: Long, note: String, dateIso: String, type: TxType): Long =
        helper.writableDatabase.insert("expenses", null, ContentValues().apply {
            put("amount_cents", amountCents); put("category_id", categoryId); put("note", note.trim())
            put("date_iso", dateIso); put("transaction_type", type.name)
        })

    fun updateTransaction(id: Long, amountCents: Long, categoryId: Long, note: String, dateIso: String, type: TxType): Boolean =
        helper.writableDatabase.update("expenses", ContentValues().apply {
            put("amount_cents", amountCents); put("category_id", categoryId); put("note", note.trim())
            put("date_iso", dateIso); put("transaction_type", type.name)
        }, "id=?", arrayOf(id.toString())) > 0

    fun duplicateTransaction(id: Long): Long {
        val tx = transactionById(id) ?: return -1
        return addTransaction(tx.amountCents, tx.categoryId, tx.note, LocalDate.now().toString(), tx.type)
    }

    fun deleteTransaction(id: Long) {
        val db = helper.writableDatabase
        db.delete("recurring_instances", "expense_id=?", arrayOf(id.toString()))
        db.delete("expenses", "id=?", arrayOf(id.toString()))
    }

    fun transactionById(id: Long): Transaction? =
        helper.readableDatabase.rawQuery(
            """
            SELECT e.id,e.amount_cents,e.category_id,c.name,c.icon,c.color_argb,e.note,e.date_iso,e.transaction_type
            FROM expenses e JOIN categories c ON c.id=e.category_id WHERE e.id=?
            """.trimIndent(), arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) null else Transaction(
                c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getLong(5),
                c.getString(6), c.getString(7), TxType.valueOf(c.getString(8))
            )
        }

    fun transactionsForMonth(yearMonth: String, query: String = "", categoryId: Long? = null, type: TxType? = null): List<Transaction> {
        val out = mutableListOf<Transaction>()
        val where = mutableListOf("substr(e.date_iso,1,7)=?")
        val args = mutableListOf(yearMonth)
        if (query.isNotBlank()) {
            where += "(e.note LIKE ? OR c.name LIKE ?)"
            val q = "%${query.trim()}%"; args += q; args += q
        }
        if (categoryId != null) { where += "e.category_id=?"; args += categoryId.toString() }
        if (type != null) { where += "e.transaction_type=?"; args += type.name }

        helper.readableDatabase.rawQuery(
            """
            SELECT e.id,e.amount_cents,e.category_id,c.name,c.icon,c.color_argb,e.note,e.date_iso,e.transaction_type
            FROM expenses e JOIN categories c ON c.id=e.category_id
            WHERE ${where.joinToString(" AND ")}
            ORDER BY e.date_iso DESC,e.id DESC
            """.trimIndent(), args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) out += Transaction(
                c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getLong(5),
                c.getString(6), c.getString(7), TxType.valueOf(c.getString(8))
            )
        }
        return out
    }

    fun categoryTotals(yearMonth: String, type: TxType = TxType.EXPENSE): List<CategoryTotal> {
        val out = mutableListOf<CategoryTotal>()
        helper.readableDatabase.rawQuery(
            """
            SELECT c.id,c.name,c.icon,c.color_argb,SUM(e.amount_cents)
            FROM expenses e JOIN categories c ON c.id=e.category_id
            WHERE substr(e.date_iso,1,7)=? AND e.transaction_type=?
            GROUP BY c.id,c.name,c.icon,c.color_argb ORDER BY SUM(e.amount_cents) DESC
            """.trimIndent(), arrayOf(yearMonth, type.name)
        ).use { c ->
            while (c.moveToNext()) out += CategoryTotal(
                c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)
            )
        }
        return out
    }

    fun monthSummary(yearMonth: String): MonthSummary {
        var expense = 0L; var income = 0L
        helper.readableDatabase.rawQuery(
            """
            SELECT transaction_type,COALESCE(SUM(amount_cents),0)
            FROM expenses WHERE substr(date_iso,1,7)=?
            GROUP BY transaction_type
            """.trimIndent(), arrayOf(yearMonth)
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == TxType.INCOME.name) income = c.getLong(1) else expense = c.getLong(1)
            }
        }
        return MonthSummary(yearMonth, expense, income)
    }

    fun history(endMonth: YearMonth, months: Int): List<MonthSummary> =
        (months - 1 downTo 0).map { monthSummary(endMonth.minusMonths(it.toLong()).toString()) }

    fun yearHistory(year: Int): List<MonthSummary> =
        (1..12).map { monthSummary(YearMonth.of(year, it).toString()) }

    fun getMonthlyBudget(yearMonth: String): Long =
        helper.readableDatabase.rawQuery(
            "SELECT amount_cents FROM monthly_budgets WHERE year_month=?", arrayOf(yearMonth)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    fun setMonthlyBudget(yearMonth: String, cents: Long) {
        val db = helper.writableDatabase
        if (cents <= 0) db.delete("monthly_budgets","year_month=?",arrayOf(yearMonth))
        else db.insertWithOnConflict("monthly_budgets", null, ContentValues().apply {
            put("year_month", yearMonth); put("amount_cents", cents)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun categoryBudgets(yearMonth: String): List<CategoryBudget> {
        val out = mutableListOf<CategoryBudget>()
        helper.readableDatabase.rawQuery(
            """
            SELECT c.id,c.name,COALESCE(b.amount_cents,0)
            FROM categories c LEFT JOIN category_budgets b
            ON b.category_id=c.id AND b.year_month=?
            ORDER BY c.name COLLATE NOCASE
            """.trimIndent(), arrayOf(yearMonth)
        ).use { c ->
            while (c.moveToNext()) out += CategoryBudget(c.getLong(0),c.getString(1),c.getLong(2))
        }
        return out
    }

    fun setCategoryBudget(yearMonth: String, categoryId: Long, cents: Long) {
        val db = helper.writableDatabase
        if (cents <= 0) db.delete("category_budgets","year_month=? AND category_id=?",
            arrayOf(yearMonth,categoryId.toString()))
        else db.insertWithOnConflict("category_budgets", null, ContentValues().apply {
            put("year_month",yearMonth); put("category_id",categoryId); put("amount_cents",cents)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recurringTransactions(): List<RecurringTransaction> {
        val out = mutableListOf<RecurringTransaction>()
        helper.readableDatabase.rawQuery(
            """
            SELECT r.id,r.amount_cents,r.category_id,c.name,r.note,r.day_of_month,r.active,r.transaction_type
            FROM recurring_expenses r JOIN categories c ON c.id=r.category_id
            ORDER BY r.day_of_month,c.name
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) out += RecurringTransaction(
                c.getLong(0),c.getLong(1),c.getLong(2),c.getString(3),c.getString(4),c.getInt(5),
                c.getInt(6)==1,TxType.valueOf(c.getString(7))
            )
        }
        return out
    }

    fun addRecurring(amountCents: Long, categoryId: Long, note: String, day: Int, type: TxType): Long =
        helper.writableDatabase.insert("recurring_expenses", null, ContentValues().apply {
            put("amount_cents",amountCents); put("category_id",categoryId); put("note",note.trim())
            put("day_of_month",day.coerceIn(1,31)); put("active",1); put("transaction_type",type.name)
        })

    fun setRecurringActive(id: Long, active: Boolean) {
        helper.writableDatabase.update("recurring_expenses", ContentValues().apply {
            put("active", if(active) 1 else 0)
        },"id=?",arrayOf(id.toString()))
    }

    fun deleteRecurring(id: Long) {
        helper.writableDatabase.delete("recurring_expenses","id=?",arrayOf(id.toString()))
    }

    fun materializeRecurring(yearMonth: YearMonth) {
        val db = helper.writableDatabase
        val ym = yearMonth.toString()
        db.beginTransaction()
        try {
            val rows = recurringTransactions().filter { it.active }
            rows.forEach { r ->
                val exists = db.rawQuery(
                    "SELECT COUNT(*) FROM recurring_instances WHERE recurring_id=? AND year_month=?",
                    arrayOf(r.id.toString(),ym)
                ).use { c -> c.moveToFirst(); c.getInt(0)>0 }
                if (!exists) {
                    val day = r.dayOfMonth.coerceAtMost(yearMonth.lengthOfMonth())
                    val txId = db.insert("expenses",null,ContentValues().apply {
                        put("amount_cents",r.amountCents); put("category_id",r.categoryId)
                        put("note",if(r.note.isBlank()) "Movimento ricorrente" else r.note)
                        put("date_iso",yearMonth.atDay(day).toString()); put("transaction_type",r.type.name)
                    })
                    if (txId > 0) db.insert("recurring_instances",null,ContentValues().apply {
                        put("recurring_id",r.id); put("year_month",ym); put("expense_id",txId)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun exportCsv(yearMonth: String): String {
        val sb = StringBuilder("Data;Tipo;Categoria;Descrizione;Importo\n")
        transactionsForMonth(yearMonth).sortedBy { it.dateIso }.forEach { t ->
            val note = t.note.replace("\"","\"\"")
            val amount = "%.2f".format(java.util.Locale.ITALY, t.amountCents/100.0)
            sb.append("${t.dateIso};${if(t.type==TxType.EXPENSE)"Uscita" else "Entrata"};")
                .append("\"${t.categoryName.replace("\"","\"\"")}\";\"$note\";$amount\n")
        }
        return sb.toString()
    }

    fun exportBackupJson(): String {
        val root = JSONObject().put("schema",3).put("exported_at",LocalDate.now().toString())

        fun table(name: String, cols: List<String>): JSONArray {
            val arr = JSONArray()
            helper.readableDatabase.rawQuery("SELECT ${cols.joinToString(",")} FROM $name",null).use { c ->
                while(c.moveToNext()) {
                    val o = JSONObject()
                    cols.forEachIndexed { i,col ->
                        when(c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(col,c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(col,c.getDouble(i))
                            android.database.Cursor.FIELD_TYPE_NULL -> o.put(col,JSONObject.NULL)
                            else -> o.put(col,c.getString(i))
                        }
                    }
                    arr.put(o)
                }
            }
            return arr
        }

        root.put("categories",table("categories",listOf("id","name","icon","color_argb")))
        root.put("expenses",table("expenses",listOf("id","amount_cents","category_id","note","date_iso","transaction_type")))
        root.put("monthly_budgets",table("monthly_budgets",listOf("year_month","amount_cents")))
        root.put("category_budgets",table("category_budgets",listOf("year_month","category_id","amount_cents")))
        root.put("recurring_expenses",table("recurring_expenses",listOf("id","amount_cents","category_id","note","day_of_month","active","transaction_type")))
        root.put("recurring_instances",table("recurring_instances",listOf("recurring_id","year_month","expense_id")))
        return root.toString(2)
    }

    fun importBackupJson(json: String): Boolean {
        val root = try { JSONObject(json) } catch (_: Exception) { return false }
        val schema = root.optInt("schema",0)
        if (schema !in 1..3) return false
        val db = helper.writableDatabase

        db.beginTransaction()
        return try {
            db.delete("recurring_instances",null,null)
            db.delete("category_budgets",null,null)
            db.delete("monthly_budgets",null,null)
            db.delete("expenses",null,null)
            db.delete("recurring_expenses",null,null)
            db.delete("categories",null,null)

            val cats = root.optJSONArray("categories") ?: return false
            for(i in 0 until cats.length()) {
                val o = cats.getJSONObject(i)
                db.insertOrThrow("categories",null,ContentValues().apply {
                    put("id",o.getLong("id")); put("name",o.getString("name"))
                    put("icon",o.optString("icon","●")); put("color_argb",o.optLong("color_argb",0xFF5B6CFF))
                })
            }

            val txs = root.optJSONArray("expenses") ?: JSONArray()
            for(i in 0 until txs.length()) {
                val o = txs.getJSONObject(i)
                db.insertOrThrow("expenses",null,ContentValues().apply {
                    put("id",o.getLong("id")); put("amount_cents",o.getLong("amount_cents"))
                    put("category_id",o.getLong("category_id")); put("note",o.optString("note",""))
                    put("date_iso",o.getString("date_iso")); put("transaction_type",o.optString("transaction_type","EXPENSE"))
                })
            }

            fun insertSimple(arrayName:String,tableName:String,cols:List<String>,defaults:Map<String,Any> = emptyMap()) {
                val arr = root.optJSONArray(arrayName) ?: return
                for(i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    db.insertOrThrow(tableName,null,ContentValues().apply {
                        cols.forEach { col ->
                            val value = if(o.has(col)) o.opt(col) else defaults[col]
                            when(value) {
                                is Number -> put(col,value.toLong())
                                JSONObject.NULL,null -> putNull(col)
                                else -> put(col,value.toString())
                            }
                        }
                    })
                }
            }
            insertSimple("monthly_budgets","monthly_budgets",listOf("year_month","amount_cents"))
            insertSimple("category_budgets","category_budgets",listOf("year_month","category_id","amount_cents"))
            insertSimple("recurring_expenses","recurring_expenses",
                listOf("id","amount_cents","category_id","note","day_of_month","active","transaction_type"),
                mapOf("transaction_type" to "EXPENSE"))
            insertSimple("recurring_instances","recurring_instances",listOf("recurring_id","year_month","expense_id"))
            db.setTransactionSuccessful(); true
        } catch (_: Exception) { false }
        finally { db.endTransaction() }
    }
}
