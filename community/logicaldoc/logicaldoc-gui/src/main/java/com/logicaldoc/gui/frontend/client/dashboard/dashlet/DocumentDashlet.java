package com.logicaldoc.gui.frontend.client.dashboard.dashlet;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.logicaldoc.gui.common.client.Constants;
import com.logicaldoc.gui.common.client.Session;
import com.logicaldoc.gui.common.client.beans.GUIDashlet;
import com.logicaldoc.gui.common.client.beans.GUIDocument;
import com.logicaldoc.gui.common.client.data.DocumentsDS;
import com.logicaldoc.gui.common.client.formatters.DateCellFormatter;
import com.logicaldoc.gui.common.client.i18n.I18N;
import com.logicaldoc.gui.common.client.log.Log;
import com.logicaldoc.gui.common.client.util.AwesomeFactory;
import com.logicaldoc.gui.common.client.util.Util;
import com.logicaldoc.gui.common.client.widgets.RefreshableListGrid;
import com.logicaldoc.gui.frontend.client.document.DocumentsPanel;
import com.logicaldoc.gui.frontend.client.services.DocumentService;
import com.smartgwt.client.data.Record;
import com.smartgwt.client.types.Alignment;
import com.smartgwt.client.types.ListGridFieldType;
import com.smartgwt.client.types.SelectionStyle;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.events.CellContextClickEvent;
import com.smartgwt.client.widgets.grid.events.CellContextClickHandler;
import com.smartgwt.client.widgets.grid.events.CellDoubleClickEvent;
import com.smartgwt.client.widgets.grid.events.CellDoubleClickHandler;
import com.smartgwt.client.widgets.grid.events.DataArrivedEvent;
import com.smartgwt.client.widgets.grid.events.DataArrivedHandler;
import com.smartgwt.client.widgets.menu.Menu;
import com.smartgwt.client.widgets.menu.MenuItem;
import com.smartgwt.client.widgets.menu.events.MenuItemClickEvent;

public class DocumentDashlet extends Dashlet {

	private DocumentsDS dataSource;

	protected RefreshableListGrid list;

	protected int status;

	public DocumentDashlet(GUIDashlet guiDashlet) {
		super(guiDashlet);
		init();
	}

	private void init() {
		ListGridField version = new ListGridField("version", I18N.message("version"), 70);
		ListGridField lastModified = new ListGridField("lastModified", I18N.message("date"), 110);
		lastModified.setAlign(Alignment.CENTER);
		lastModified.setType(ListGridFieldType.DATE);
		lastModified.setCellFormatter(new DateCellFormatter(false));
		lastModified.setCanFilter(false);
		ListGridField fileName = new ListGridField("filename", I18N.message("filename"));
		ListGridField icon = new ListGridField("icon", " ", 24);
		icon.setType(ListGridFieldType.IMAGE);
		icon.setCanSort(false);
		icon.setAlign(Alignment.CENTER);
		icon.setShowDefaultContextMenu(false);
		icon.setImageURLPrefix(Util.imagePrefix());
		icon.setImageURLSuffix(".png");
		icon.setCanFilter(false);

		list = new RefreshableListGrid();
		list.setEmptyMessage(I18N.message("notitemstoshow"));
		list.setCanFreezeFields(true);
		list.setAutoFetchData(true);
		list.setShowHeader(false);
		list.setCanSelectAll(false);
		list.setSelectionType(SelectionStyle.NONE);
		list.setHeight100();
		list.setBorder("0px");
		list.setDataSource(getDataSource());
		list.setFields(icon, fileName, version, lastModified);

		list.addCellContextClickHandler(new CellContextClickHandler() {
			@Override
			public void onCellContextClick(CellContextClickEvent event) {
				if (event != null)
					event.cancel();
				Record record = event.getRecord();
				DocumentService.Instance.get().getById(Long.parseLong(record.getAttributeAsString("id")),
						new AsyncCallback<GUIDocument>() {

							@Override
							public void onFailure(Throwable caught) {
								Log.serverError(caught);
							}

							@Override
							public void onSuccess(GUIDocument document) {
								Menu contextMenu = prepareContextMenu(document);
								contextMenu.showContextMenu();
							}
						});
			}
		});

		list.addCellDoubleClickHandler(new CellDoubleClickHandler() {
			@Override
			public void onCellDoubleClick(CellDoubleClickEvent event) {
				Record record = event.getRecord();
				DocumentsPanel.get().openInFolder(Long.parseLong(record.getAttributeAsString("folderId")),
						Long.parseLong(record.getAttributeAsString("id")));
			}
		});

		// Count the total of events and the total of unchecked events
		list.addDataArrivedHandler(new DataArrivedHandler() {
			@Override
			public void onDataArrived(DataArrivedEvent event) {
				Record[] records = list.getRecordList().toArray();
				int unread = 0;
				for (Record record : records) {
					if (record.getAttributeAsBoolean("new"))
						unread++;
				}

				String icn = "file";
				String title = I18N.message(guiDashlet.getTitle());

				if ("checkout".equals(guiDashlet.getName())) {
					icn = "edit";
				} else if ("locked".equals(guiDashlet.getName())) {
					icn = "lock-alt";
				}

				if (unread > 0 && !"file".equals(icn))
					title = "<b>" + title + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;" + I18N.message("newitems")
							+ ": " + unread + "</b>";

				setTitle(AwesomeFactory.getIconHtml(icn, title));
			}
		});

		addItem(list);

		getDataSource();
	}

	private DocumentsDS getDataSource() {
		return new DocumentsDS(getDataSourceUrl(), I18N.getLocale());
	}

	@Override
	public void destroy() {
		super.destroy();
		if (dataSource != null)
			dataSource.destroy();
	}

	@Override
	protected Menu prepareContextMenu(final GUIDocument document) {
		Menu contextMenu = super.prepareContextMenu(document);
		if (document.getStatus() == Constants.DOC_LOCKED || document.getStatus() == Constants.DOC_CHECKED_OUT) {
			MenuItem unlock = new MenuItem();
			unlock.setTitle(I18N.message("unlock"));
			unlock.addClickHandler(new com.smartgwt.client.widgets.menu.events.ClickHandler() {
				public void onClick(MenuItemClickEvent event) {
					DocumentService.Instance.get().unlock(new long[] { document.getId() }, new AsyncCallback<Void>() {
						@Override
						public void onFailure(Throwable caught) {
							Log.serverError(caught);
						}

						@Override
						public void onSuccess(Void result) {
							Session.get().getUser().setLockedDocs(Session.get().getUser().getLockedDocs() - 1);
							list.removeSelectedData();
							list.refresh(getDataSource());
						}
					});
				}
			});
			contextMenu.addItem(unlock);
		}
		return contextMenu;
	}

	@Override
	protected void refresh() {
		list.refresh(getDataSource());
	}
}