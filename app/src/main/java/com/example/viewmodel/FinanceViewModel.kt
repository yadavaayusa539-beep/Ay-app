package com.example.viewmodel

import android.app.Application
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Budget
import com.example.data.Expense
import com.example.data.FinanceDatabase
import com.example.data.FinanceRepository
import com.example.data.Subscription
import com.example.receiver.SubscriptionReminderReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class FinanceViewModel(
    application: Application,
    private val repository: FinanceRepository
) : AndroidViewModel(application) {

    // Active tabs: 0 = Quick Add & Tracker, 1 = Subscriptions & Reminders, 2 = History
    var activeTab by mutableStateOf(0)

    // Form states for Expense
    var expenseAmount by mutableStateOf("")
    var expenseCategory by mutableStateOf("Khana")
    var expenseNote by mutableStateOf("")

    // Form states for Subscription
    var subName by mutableStateOf("")
    var subAmount by mutableStateOf("")
    var subCategory by mutableStateOf("Bill")
    var subDaysUntilDue by mutableStateOf("3") // Simple offset: days from today

    // Budget Editor states
    var budgetAmountInput by mutableStateOf("")
    var isEditingBudget by mutableStateOf(false)

    // UI Feedback or Toast simulations
    var feedbackMessage by mutableStateOf<String?>(null)

    // Database flows
    val expenses: StateFlow<List<Expense>> = repository.allExpenses
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val subscriptions: StateFlow<List<Subscription>> = repository.allSubscriptions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val budgetState: StateFlow<Budget?> = repository.budget
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Computed states
    val currentMonthExpenses = expenses.combine(MutableStateFlow(System.currentTimeMillis())) { list, now ->
        list.filter { isTimestampInCurrentMonth(it.timestamp, now) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalSpentCurrentMonth = currentMonthExpenses.combine(MutableStateFlow(0.0)) { list, _ ->
        list.sumOf { it.amount }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    init {
        // Initialize default budget if not set
        viewModelScope.launch {
            val existing = repository.getBudgetDirect()
            if (existing == null) {
                repository.insertBudget(Budget(monthlyLimit = 10000.0))
            }
        }
    }

    fun updateBudget(newLimit: Double) {
        viewModelScope.launch {
            repository.insertBudget(Budget(monthlyLimit = newLimit))
            feedbackMessage = "Budget updated to ₹${newLimit.toInt()}"
            isEditingBudget = false
        }
    }

    fun addExpense() {
        val amt = expenseAmount.toDoubleOrNull()
        if (amt == null || amt <= 0) {
            feedbackMessage = "Please enter a valid amount"
            return
        }
        val cat = expenseCategory.trim()
        if (cat.isEmpty()) {
            feedbackMessage = "Please select or type a category"
            return
        }

        viewModelScope.launch {
            val expense = Expense(
                amount = amt,
                category = cat,
                timestamp = System.currentTimeMillis(),
                note = expenseNote.trim()
            )
            repository.insertExpense(expense)
            feedbackMessage = "Saved ₹${amt.toInt()} for $cat!"
            
            // Clear inputs
            expenseAmount = ""
            expenseNote = ""
        }
    }

    fun quickAddExpense(amt: Double, category: String) {
        viewModelScope.launch {
            val expense = Expense(
                amount = amt,
                category = category,
                timestamp = System.currentTimeMillis(),
                note = "Quick Entry"
            )
            repository.insertExpense(expense)
            feedbackMessage = "Quick saved ₹${amt.toInt()} for $category!"
        }
    }

    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            repository.deleteExpense(expense)
            feedbackMessage = "Expense deleted"
        }
    }

    fun deleteAllExpenses() {
        viewModelScope.launch {
            repository.deleteAllExpenses()
            feedbackMessage = "All expenses cleared!"
        }
    }

    fun addSubscription() {
        val name = subName.trim()
        val amt = subAmount.toDoubleOrNull()
        val days = subDaysUntilDue.toIntOrNull() ?: 3

        if (name.isEmpty()) {
            feedbackMessage = "Please enter subscription name"
            return
        }
        if (amt == null || amt <= 0) {
            feedbackMessage = "Please enter valid amount"
            return
        }

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, days)
        val dueDateMillis = calendar.timeInMillis

        viewModelScope.launch {
            val subscription = Subscription(
                name = name,
                amount = amt,
                nextDueDateMillis = dueDateMillis,
                category = subCategory
            )
            repository.insertSubscription(subscription)
            feedbackMessage = "Subscription '$name' added!"
            
            // Schedule alert notification for 24 hours before
            scheduleNotificationForSubscription(getApplication(), subscription)

            // Clear inputs
            subName = ""
            subAmount = ""
            subDaysUntilDue = "3"
        }
    }

    fun deleteSubscription(subscription: Subscription) {
        viewModelScope.launch {
            repository.deleteSubscription(subscription)
            feedbackMessage = "Subscription deleted"
        }
    }

    fun markSubscriptionAsPaid(subscription: Subscription) {
        viewModelScope.launch {
            // 1. Add corresponding expense
            val expense = Expense(
                amount = subscription.amount,
                category = "Bill",
                timestamp = System.currentTimeMillis(),
                note = "Subscription: ${subscription.name}"
            )
            repository.insertExpense(expense)

            // 2. Increment due date by 1 month
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = subscription.nextDueDateMillis
            calendar.add(Calendar.MONTH, 1)

            val updatedSub = subscription.copy(
                nextDueDateMillis = calendar.timeInMillis
            )
            repository.updateSubscription(updatedSub)
            feedbackMessage = "Paid ${subscription.name}! Expense recorded & due date updated."

            // Reschedule notification
            scheduleNotificationForSubscription(getApplication(), updatedSub)
        }
    }

    fun clearFeedback() {
        feedbackMessage = null
    }

    private fun isTimestampInCurrentMonth(timestamp: Long, now: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val currentMonth = cal.get(Calendar.MONTH)
        val currentYear = cal.get(Calendar.YEAR)

        cal.timeInMillis = timestamp
        val itemMonth = cal.get(Calendar.MONTH)
        val itemYear = cal.get(Calendar.YEAR)

        return currentMonth == itemMonth && currentYear == itemYear
    }

    private fun scheduleNotificationForSubscription(context: Context, sub: Subscription) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // Calculate 24 hours before due date
            val triggerAtMillis = sub.nextDueDateMillis - (24 * 60 * 60 * 1000)
            
            // If the 24h-before mark is already in the past, schedule it 5 seconds from now for demo purposes!
            val actualTrigger = if (triggerAtMillis < System.currentTimeMillis()) {
                System.currentTimeMillis() + 5000 // 5 seconds from now
            } else {
                triggerAtMillis
            }

            val intent = Intent(context, SubscriptionReminderReceiver::class.java).apply {
                putExtra("sub_name", sub.name)
                putExtra("sub_amount", sub.amount)
                putExtra("sub_id", sub.id)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sub.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Schedule the alarm
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, actualTrigger, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, actualTrigger, pendingIntent)
            }
            Log.d("FinanceViewModel", "Scheduled alarm for subscription: ${sub.name} at trigger time: $actualTrigger")
        } catch (e: Exception) {
            Log.e("FinanceViewModel", "Failed to schedule notification alarm: ${e.message}", e)
        }
    }

    companion object {
        fun provideFactory(application: Application, repository: FinanceRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return FinanceViewModel(application, repository) as T
                }
            }
        }
    }
}
