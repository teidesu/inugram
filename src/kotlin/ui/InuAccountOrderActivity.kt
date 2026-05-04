package desu.inugram.ui

import android.content.Context
import android.view.View
import desu.inugram.helpers.AccountOrderHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.SettingsActivity

class InuAccountOrderActivity : InuSettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuReorderAccounts)

    override fun createView(context: Context): View {
        return super.createView(context).also {
            listView.listenReorder { _, sectionItems ->
                val order = sectionItems
                    .filter { it.instanceOf(SettingsActivity.AccountCell.Factory::class.java) }
                    .map { it.intValue }
                AccountOrderHelper.setOrder(order)
            }
            listView.allowReorder(true)
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val accounts = mutableListOf<Int>()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated) accounts.add(a)
        }
        AccountOrderHelper.sort(accounts)

        items.add(UItem.asShadow(LocaleController.getString(R.string.InuReorderAccountsInfo)))
        adapter.reorderSectionStart()
        for ((i, account) in accounts.withIndex()) {
            items.add(SettingsActivity.AccountCell.Factory.of(i, account))
        }
        adapter.reorderSectionEnd()
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {}
}
