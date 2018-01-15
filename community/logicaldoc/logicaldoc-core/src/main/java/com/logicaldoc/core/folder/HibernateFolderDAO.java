package com.logicaldoc.core.folder;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.RunLevel;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.History;
import com.logicaldoc.core.document.Tag;
import com.logicaldoc.core.document.dao.DocumentDAO;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.security.Group;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.User;
import com.logicaldoc.core.security.dao.TenantDAO;
import com.logicaldoc.core.security.dao.UserDAO;
import com.logicaldoc.core.store.Storer;
import com.logicaldoc.util.Context;
import com.logicaldoc.util.StringUtil;
import com.logicaldoc.util.html.HTMLSanitizer;
import com.logicaldoc.util.sql.SqlUtil;

/**
 * Hibernate implementation of <code>FolderDAO</code>
 * 
 * @author Marco Meschieri - Logical Objects
 * @since 6.0
 */
@SuppressWarnings("unchecked")
public class HibernateFolderDAO extends HibernatePersistentObjectDAO<Folder> implements FolderDAO {

	private UserDAO userDAO;

	private FolderHistoryDAO historyDAO;

	private Storer storer;

	protected HibernateFolderDAO() {
		super(Folder.class);
		super.log = LoggerFactory.getLogger(HibernateFolderDAO.class);
	}

	public UserDAO getUserDAO() {
		return userDAO;
	}

	public void setUserDAO(UserDAO userDAO) {
		this.userDAO = userDAO;
	}

	@Override
	public boolean store(Folder folder) {
		return store(folder, null);
	}

	@Override
	public boolean store(Folder folder, FolderHistory transaction) {
		boolean result = true;

		try {
			if (!folder.getName().equals("/")) {
				// To avoid java script and xml injection
				folder.setName(HTMLSanitizer.sanitizeSimpleText(folder.getName()));

				// Remove possible path separators
				folder.setName(folder.getName().replace("/", ""));
				folder.setName(folder.getName().replace("\\", ""));
			}

			Folder parent = findFolder(folder.getParentId());
			folder.setParentId(parent.getId());

			if (folder.getFoldRef() == null) {
				if (folder.getSecurityRef() != null)
					folder.getFolderGroups().clear();

				if (transaction != null) {
					folder.setCreator(transaction.getUser().getFullName());
					folder.setCreatorId(transaction.getUserId());
					if (folder.getId() == 0 && transaction.getEvent() == null)
						transaction.setEvent(FolderEvent.CREATED.toString());
				}

				List<Folder> aliases = findAliases(folder.getId(), folder.getTenantId());
				for (Folder alias : aliases) {
					alias.setDeleted(folder.getDeleted());
					alias.setDeleteUserId(folder.getDeleteUserId());
					if (folder.getSecurityRef() != null)
						alias.setSecurityRef(folder.getSecurityRef());
					else
						alias.setSecurityRef(folder.getId());
					saveOrUpdate(alias);
				}
			}

			Set<Tag> src = folder.getTags();
			if (src != null && src.size() > 0) {
				// Trim too long tags
				Set<Tag> dst = new HashSet<Tag>();
				for (Tag str : src) {
					str.setTenantId(folder.getTenantId());
					String s = str.getTag();
					if (s != null) {
						if (s.length() > 255) {
							s = s.substring(0, 255);
							str.setTag(s);
						}
						if (!dst.contains(str))
							dst.add(str);
					}
				}
				folder.setTags(dst);
				folder.setTgs(folder.getTagsString());
			}

			saveOrUpdate(folder);
			saveFolderHistory(folder, transaction);
		} catch (Throwable e) {
			log.error(e.getMessage(), e);
			result = false;
		}

		return result;
	}

	@Override
	@SuppressWarnings("rawtypes")
	public List<Folder> findByUserId(long userId) {
		List<Folder> coll = new ArrayList<Folder>();

		try {
			User user = userDAO.findById(userId);
			if (user == null)
				return coll;

			// The administrators can see all folders
			if (user.isMemberOf("admin"))
				return findAll();

			Set<Group> precoll = user.getGroups();
			if (!precoll.isEmpty()) {
				// First of all collect all folders that define it's own
				// policies
				StringBuffer query = new StringBuffer("select distinct(_folder) from Folder _folder  ");
				query.append(" left join _folder.folderGroups as _group ");
				query.append(" where _group.groupId in (");

				boolean first = true;
				Iterator iter = precoll.iterator();
				while (iter.hasNext()) {
					if (!first)
						query.append(",");
					Group ug = (Group) iter.next();
					query.append(Long.toString(ug.getId()));
					first = false;
				}
				query.append(")");
				coll = (List<Folder>) find(query.toString(), user.getTenantId());

				if (coll.isEmpty()) {
					return coll;
				} else {

					// Now collect all folders that references the policies of
					// the previously found folders
					List<Folder> tmp = new ArrayList<Folder>();
					query = new StringBuffer("select _folder from Folder _folder  where _folder.securityRef in (");
					first = true;
					for (Folder folder : coll) {
						if (!first)
							query.append(",");
						query.append(Long.toString(folder.getId()));
						first = false;
					}
					query.append(")");
					tmp = (List<Folder>) find(query.toString(), user.getTenantId());

					for (Folder folder : tmp) {
						if (!coll.contains(folder))
							coll.add(folder);
					}
				}
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		return coll;
	}

	@Override
	@SuppressWarnings("rawtypes")
	public List<Folder> findByUserId(long userId, long parentId) {
		List<Folder> coll = new ArrayList<Folder>();

		try {
			User user = userDAO.findById(userId);
			if (user == null)
				return coll;
			if (user.isMemberOf("admin"))
				return findByWhere("_entity.id!=_entity.parentId and _entity.parentId=" + parentId,
						" order by _entity.name ", null);
			/*
			 * Search for all those folders that defines its own security
			 * policies
			 */
			StringBuffer query1 = new StringBuffer();
			Set<Group> precoll = user.getGroups();
			if (precoll.isEmpty())
				return coll;

			query1.append("select distinct(_entity) from Folder _entity ");
			query1.append(" left join _entity.folderGroups as _group");
			query1.append(" where _group.groupId in (");

			boolean first = true;
			Iterator iter = precoll.iterator();
			while (iter.hasNext()) {
				if (!first)
					query1.append(",");
				Group ug = (Group) iter.next();
				query1.append(Long.toString(ug.getId()));
				first = false;
			}
			query1.append(") and _entity.parentId = ?1 and _entity.id!=_entity.parentId");

			coll = (List<Folder>) findByQuery(query1.toString(), new Object[] { parentId }, null);

			/*
			 * Now search for all other folders that references accessible
			 * folders
			 */
			StringBuffer query2 = new StringBuffer(
					"select _entity from Folder _entity where _entity.deleted=0 and _entity.parentId=?1 ");
			query2.append(" and _entity.securityRef in (");
			query2.append("    select distinct(B.id) from Folder B ");
			query2.append(" left join B.folderGroups as _group");
			query2.append(" where _group.groupId in (");

			first = true;
			iter = precoll.iterator();
			while (iter.hasNext()) {
				if (!first)
					query2.append(",");
				Group ug = (Group) iter.next();
				query2.append(Long.toString(ug.getId()));
				first = false;
			}
			query2.append("))");

			List<Folder> coll2 = (List<Folder>) findByQuery(query2.toString(), new Long[] { parentId }, null);
			for (Folder folder : coll2) {
				if (!coll.contains(folder))
					coll.add(folder);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		Collections.sort(coll, new Comparator<Folder>() {
			@Override
			public int compare(Folder o1, Folder o2) {
				return -1 * o1.getName().compareTo(o2.getName());
			}
		});
		return coll;
	}

	@Override
	public List<Folder> findChildren(long parentId, Integer max) {
		Folder parent = findFolder(parentId);
		return findByWhere("_entity.parentId = ?1 and _entity.id!=_entity.parentId", new Object[] { parent.getId() },
				"order by _entity.name", max);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public List<Folder> findChildren(long parentId, long userId) {
		List<Folder> coll = new ArrayList<Folder>();
		try {
			Folder parent = findFolder(parentId);

			User user = userDAO.findById(userId);
			if (user.isMemberOf("admin"))
				return findChildren(parent.getId(), null);

			Set<Group> groups = user.getGroups();
			if (groups.isEmpty())
				return coll;

			/*
			 * Search for the folders that define its own policies
			 */
			StringBuffer query1 = new StringBuffer("select distinct(_entity) from Folder _entity  ");
			query1.append(" left join _entity.folderGroups as _group ");
			query1.append(" where _group.groupId in (");

			boolean first = true;
			Iterator iter = groups.iterator();
			while (iter.hasNext()) {
				if (!first)
					query1.append(",");
				Group ug = (Group) iter.next();
				query1.append(Long.toString(ug.getId()));
				first = false;
			}
			query1.append(") and _entity.parentId=" + parent.getId());
			query1.append(" and not(_entity.id=" + parent.getId() + ")");

			coll = (List<Folder>) findByQuery(query1.toString(), null, null);

			/*
			 * Now search for all other folders that references accessible
			 * folders
			 */
			StringBuffer query2 = new StringBuffer(
					"select _entity from Folder _entity where _entity.deleted=0 and _entity.parentId=?1 ");
			query2.append(" and _entity.securityRef in (");
			query2.append("    select distinct(B.id) from Folder B ");
			query2.append(" left join B.folderGroups as _group");
			query2.append(" where _group.groupId in (");

			first = true;
			iter = groups.iterator();
			while (iter.hasNext()) {
				if (!first)
					query2.append(",");
				Group ug = (Group) iter.next();
				query2.append(Long.toString(ug.getId()));
				first = false;
			}
			query2.append("))");
			query2.append(" and not(_entity.id=" + parent.getId() + ")");

			List<Folder> coll2 = (List<Folder>) findByQuery(query2.toString(), new Long[] { parent.getId() }, null);
			for (Folder folder : coll2) {
				if (!coll.contains(folder))
					coll.add(folder);
			}
		} catch (Throwable e) {
			if (log.isErrorEnabled())
				log.error(e.getMessage(), e);
			return coll;
		}
		return coll;
	}

	@Override
	public List<Folder> findByParentId(long parentId) {
		List<Folder> coll = new ArrayList<Folder>();

		Folder parent = findFolder(parentId);
		if (parent == null)
			return coll;

		Set<Long> ids = findFolderIdInTree(parent.getId(), false);
		for (Long id : ids)
			if (parentId != id.longValue())
				coll.add(findFolder(id));

		return coll;
	}

	@Override
	public List<Long> findIdsByParentId(long parentId) {
		List<Folder> coll = findByParentId(parentId);
		List<Long> ids = new ArrayList<Long>();
		for (Folder folder : coll)
			ids.add(folder.getId());
		return ids;
	}

	@Override
	public boolean isPrintEnabled(long folderId, long userId) {
		return isPermissionEnabled(Permission.PRINT, folderId, userId);
	}

	@Override
	public boolean isWriteEnabled(long folderId, long userId) {
		return isPermissionEnabled(Permission.WRITE, folderId, userId);
	}

	@Override
	public boolean isDownloadEnabled(long id, long userId) {
		return isPermissionEnabled(Permission.DOWNLOAD, id, userId);
	}

	@Override
	@SuppressWarnings("rawtypes")
	public boolean isReadEnabled(long folderId, long userId) {
		boolean result = true;
		try {
			User user = userDAO.findById(userId);
			if (user == null)
				return false;
			if (user.isMemberOf("admin"))
				return true;

			long id = folderId;
			Folder folder = findById(folderId);
			if (folder == null)
				return false;
			if (folder.getSecurityRef() != null)
				id = folder.getSecurityRef().longValue();

			Set<Group> Groups = user.getGroups();
			if (Groups.isEmpty())
				return false;

			StringBuffer query = new StringBuffer("select distinct(_entity) from Folder _entity  ");
			query.append(" left join _entity.folderGroups as _group ");
			query.append(" where _group.groupId in (");

			boolean first = true;
			Iterator iter = Groups.iterator();
			while (iter.hasNext()) {
				if (!first)
					query.append(",");
				Group ug = (Group) iter.next();
				query.append(Long.toString(ug.getId()));
				first = false;
			}
			query.append(") and _entity.id=?1");

			List<FolderGroup> coll = (List<FolderGroup>) findByQuery(query.toString(), new Object[] { new Long(id) },
					null);
			result = coll.size() > 0;
		} catch (Exception e) {
			if (log.isErrorEnabled())
				log.error(e.getMessage(), e);
			result = false;
		}

		return result;
	}

	@Override
	public Collection<Long> findFolderIdByUserId(long userId, Long parentId, boolean tree) {
		return findFolderIdByUserIdAndPermission(userId, Permission.READ, parentId, tree);
	}

	@Override
	public boolean hasWriteAccess(Folder folder, long userId) {
		if (isWriteEnabled(folder.getId(), userId) == false) {
			return false;
		}

		List<Folder> children = findByParentId(folder.getId());

		for (Folder subFolder : children) {
			if (!hasWriteAccess(subFolder, userId)) {
				return false;
			}
		}

		return true;
	}

	@Override
	public List<Folder> findByGroupId(long groupId) {
		List<Folder> coll = new ArrayList<Folder>();

		// The administrators can see all folders
		if (groupId == Group.GROUPID_ADMIN)
			return findAll();

		try {
			/*
			 * Search for folders that define its own security policies
			 */
			StringBuffer query = new StringBuffer("select distinct(_entity) from Folder _entity  ");
			query.append(" left join _entity.folderGroups as _group ");
			query.append(" where _entity.deleted=0 and _group.groupId =" + groupId);

			coll = (List<Folder>) findByQuery(query.toString(), null, null);

			/*
			 * Now search for all other folders that references the previous
			 * ones
			 */
			if (!coll.isEmpty()) {
				StringBuffer query2 = new StringBuffer("select _entity from Folder _entity where _entity.deleted=0 ");
				query2.append(" and _entity.securityRef in (");
				boolean first = true;
				for (Folder folder : coll) {
					if (!first)
						query2.append(",");
					query2.append(Long.toString(folder.getId()));
					first = false;
				}
				query2.append(")");
				List<Folder> coll2 = (List<Folder>) findByQuery(query2.toString(), null, null);
				for (Folder folder : coll2) {
					if (!coll.contains(folder))
						coll.add(folder);
				}
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		return coll;
	}

	@Override
	@SuppressWarnings("rawtypes")
	public List<Long> findIdByUserId(long userId, long parentId) {
		List<Long> ids = new ArrayList<Long>();
		try {
			User user = userDAO.findById(userId);
			if (user == null)
				return ids;
			if (user.isMemberOf("admin"))
				return findIdsByWhere("_entity.parentId=" + parentId, null, null);

			StringBuffer query1 = new StringBuffer();
			Set<Group> precoll = user.getGroups();
			Iterator iter = precoll.iterator();
			if (!precoll.isEmpty()) {
				query1 = new StringBuffer("select distinct(A.ld_folderid) from ld_foldergroup A, ld_folder B "
						+ " where B.ld_deleted=0 and A.ld_folderid=B.ld_id AND (B.ld_parentid=" + parentId
						+ " OR B.ld_id=" + parentId + ")" + " AND A.ld_groupid in (");
				boolean first = true;
				while (iter.hasNext()) {
					if (!first)
						query1.append(",");
					Group ug = (Group) iter.next();
					query1.append(Long.toString(ug.getId()));
					first = false;
				}
				query1.append(")");

				ids = (List<Long>) queryForList(query1.toString(), Long.class);

				/*
				 * Now find all folders referencing the previously found ones
				 */
				StringBuffer query2 = new StringBuffer("select B.ld_id from ld_folder B where B.ld_deleted=0 ");
				query2.append(" and B.ld_parentid=" + parentId);
				query2.append("	and B.ld_securityref in (");
				query2.append(query1.toString());
				query2.append(")");

				List<Long> folderids2 = (List<Long>) queryForList(query2.toString(), Long.class);
				for (Long folderid : folderids2) {
					if (!ids.contains(folderid))
						ids.add(folderid);
				}
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		return ids;
	}

	@Override
	public List<Folder> findByName(String name, Long tenantId) {
		return findByName(null, name, tenantId, true);
	}

	@Override
	public List<Folder> findByName(Folder parent, String name, Long tenantId, boolean caseSensitive) {
		StringBuffer query = null;
		if (caseSensitive)
			query = new StringBuffer("_entity.name like '" + SqlUtil.doubleQuotes(name) + "' ");
		else
			query = new StringBuffer("lower(_entity.name) like '" + SqlUtil.doubleQuotes(name.toLowerCase()) + "' ");

		if (parent != null) {
			query.append(" AND _entity.parentId = " + parent.getId());
			if (tenantId == null)
				query.append(" AND _entity.tenantId = " + parent.getTenantId());
		}

		if (tenantId != null)
			query.append(" AND _entity.tenantId = " + tenantId);
		return findByWhere(query.toString(), null, null);
	}

	@Override
	public String computePathExtended(long folderId) {
		Folder folder = findById(folderId);
		if (folder == null)
			return null;

		Folder root = findRoot(folder.getTenantId());
		if (root == null)
			return null;

		long rootId = root.getId();

		String path = folderId != rootId ? folder.getName() : "";
		while (folder != null && folder.getId() != folder.getParentId() && folder.getId() != rootId) {
			folder = findById(folder.getParentId());
			if (folder != null)
				path = (folder.getId() != rootId ? folder.getName() : "") + "/" + path;
		}
		if (!path.startsWith("/"))
			path = "/" + path;
		return path;
	}

	/**
	 * Utility method that logs into the DB the transaction that involved the
	 * passed folder. The transaction must be provided with userId and userName.
	 * 
	 * @param folder
	 * @param transaction
	 */
	@Override
	public void saveFolderHistory(Folder folder, FolderHistory transaction) {
		if (folder == null || transaction == null || !RunLevel.current().aspectEnabled("saveHistory"))
			return;

		Folder root = findRoot(folder.getTenantId());
		if (root == null) {
			log.warn("Unable to find root for folder {}", folder);
			return;
		}

		long rootId = root.getId();

		transaction.setNotified(0);
		transaction.setFolderId(folder.getId());
		transaction.setTenantId(folder.getTenantId());

		Tenant tenant = ((TenantDAO) Context.get().getBean(TenantDAO.class)).findById(folder.getTenantId());
		if (tenant != null)
			transaction.setTenant(tenant.getName());

		transaction.setFilename(folder.getId() != rootId ? folder.getName() : "/");
		String pathExtended = transaction.getPath();
		if (StringUtils.isEmpty(pathExtended))
			pathExtended = computePathExtended(folder.getId());

		transaction.setPath(pathExtended);
		transaction.setFolder(folder);

		historyDAO.store(transaction);

		// Check if is necessary to add a new history entry for the parent
		// folder. This operation is not recursive, because we want to notify
		// only the parent folder.
		if (folder.getId() != folder.getParentId() && folder.getId() != rootId) {
			Folder parent = findById(folder.getParentId());
			// The parent folder can be 'null' when the user wants to delete a
			// folder with sub-folders under it (method 'deleteAll()').
			if (parent != null) {
				FolderHistory parentHistory = new FolderHistory();
				parentHistory.setFolderId(parent.getId());
				parentHistory.setFilename(folder.getName());
				parentHistory.setPath(pathExtended);
				parentHistory.setUser(transaction.getUser());
				parentHistory.setComment("");
				parentHistory.setSessionId(transaction.getSessionId());
				parentHistory.setComment(transaction.getComment());
				parentHistory.setPathOld(transaction.getPathOld());
				parentHistory.setFilenameOld(transaction.getFilenameOld());

				if (transaction.getEvent().equals(FolderEvent.CREATED.toString())
						|| transaction.getEvent().equals(FolderEvent.MOVED.toString())) {
					parentHistory.setEvent(FolderEvent.SUBFOLDER_CREATED.toString());
				} else if (transaction.getEvent().equals(FolderEvent.RENAMED.toString())) {
					parentHistory.setEvent(FolderEvent.SUBFOLDER_RENAMED.toString());
				} else if (transaction.getEvent().equals(FolderEvent.PERMISSION.toString())) {
					parentHistory.setEvent(FolderEvent.SUBFOLDER_PERMISSION.toString());
				} else if (transaction.getEvent().equals(FolderEvent.DELETED.toString())) {
					parentHistory.setEvent(FolderEvent.SUBFOLDER_DELETED.toString());
				} else if (transaction.getEvent().equals(FolderEvent.CHANGED.toString())) {
					parentHistory.setEvent(FolderEvent.SUBFOLDER_CHANGED.toString());
				} else if (transaction.getEvent().equals(FolderEvent.RESTORED.toString())) {
					parentHistory.setEvent(FolderEvent.SUBFOLDER_RESTORED.toString());
				}

				if (StringUtils.isNotEmpty(parentHistory.getEvent()))
					historyDAO.store(parentHistory);
			}
		}
	}

	@Override
	public List<Folder> findByNameAndParentId(String name, long parentId) {
		return findByWhere("_entity.parentId = " + parentId + " and _entity.name like '" + SqlUtil.doubleQuotes(name)
				+ "'", null, null);
	}

	@Override
	public List<Folder> findParents(long folderId) {
		Folder folder = findById(folderId);
		if (folder == null)
			return new ArrayList<Folder>();

		long rootId = findRoot(folder.getTenantId()).getId();
		List<Folder> coll = new ArrayList<Folder>();
		try {
			while (folder.getId() != rootId && folder.getId() != folder.getParentId()) {
				folder = findById(folder.getParentId());
				if (folder != null)
					coll.add(0, folder);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		return coll;
	}

	@Override
	public Folder findWorkspace(long folderId) {
		Folder folder = findById(folderId);

		if (folder != null && folder.isWorkspace())
			return folder;
		else {
			List<Folder> parents = findParents(folderId);
			for (Folder parent : parents) {
				if (!"/".equals(parent.getName()) && parent.isWorkspace())
					return parent;
			}
		}

		return null;
	}

	@Override
	public boolean isPermissionEnabled(Permission permission, long folderId, long userId) {
		Set<Permission> permissions = getEnabledPermissions(folderId, userId);
		return permissions.contains(permission);
	}

	@Override
	public void restore(long folderId, long parentId, FolderHistory transaction) {
		Folder parent = findFolder(parentId);
		bulkUpdate("set ld_deleted=0, ld_parentid=" + parent.getId()
				+ ", ld_lastmodified=CURRENT_TIMESTAMP where ld_id=" + folderId, null);

		Folder fld = findFolder(folderId);
		if (fld != null && transaction != null) {
			transaction.setEvent(FolderEvent.RESTORED.toString());
			saveFolderHistory(fld, transaction);
		}

		// Restore all the children
		Set<Long> treeIds = findFolderIdInTree(folderId, true);
		if (!treeIds.isEmpty()) {
			String idsStr = treeIds.toString().replace('[', '(').replace(']', ')');
			bulkUpdate("set ld_deleted=0, ld_lastmodified=CURRENT_TIMESTAMP where ld_deleted=1 and ld_id in " + idsStr,
					null);
			jdbcUpdate("update ld_document set ld_deleted=0, ld_lastmodified=CURRENT_TIMESTAMP where ld_deleted=1 and ld_folderid in "
					+ idsStr);
		}
	}

	@Override
	public Set<Permission> getEnabledPermissions(long folderId, long userId) {
		Set<Permission> permissions = new HashSet<Permission>();

		try {
			User user = userDAO.findById(userId);
			if (user == null)
				return permissions;

			// If the user is an administrator bypass all controls
			if (user.isMemberOf("admin")) {
				return Permission.all();
			}

			Set<Group> groups = user.getGroups();
			if (groups.isEmpty())
				return permissions;

			// If the folder defines a security ref, use another folder to find
			// the policies
			long id = folderId;
			Folder folder = findById(folderId);
			if (folder.getSecurityRef() != null) {
				id = folder.getSecurityRef().longValue();
				log.debug("Use the security reference " + id);
			}

			StringBuffer query = new StringBuffer(
					"select A.ld_write as LDWRITE, A.ld_add as LDADD, A.ld_security as LDSECURITY, A.ld_immutable as LDIMMUTABLE, A.ld_delete as LDDELETE, A.ld_rename as LDRENAME, A.ld_import as LDIMPORT, A.ld_export as LDEXPORT, A.ld_sign as LDSIGN, A.ld_archive as LDARCHIVE, A.ld_workflow as LDWORKFLOW, A.ld_download as LDDOWNLOAD, A.ld_calendar as LDCALENDAR, A.ld_subscription as LDSUBSCRIPTION, A.ld_print as LDPRINT, A.ld_password as LDPASSWORD");
			query.append(" from ld_foldergroup A");
			query.append(" where ");
			query.append(" A.ld_folderid=" + id);
			query.append(" and A.ld_groupid in (");

			boolean first = true;
			Iterator<Group> iter = groups.iterator();
			while (iter.hasNext()) {
				if (!first)
					query.append(",");
				Group ug = (Group) iter.next();
				query.append(Long.toString(ug.getId()));
				first = false;
			}
			query.append(")");

			Connection con = null;
			Statement stmt = null;
			ResultSet rs = null;

			try {
				con = getConnection();
				stmt = con.createStatement();
				rs = stmt.executeQuery(query.toString());
				while (rs.next()) {
					permissions.add(Permission.READ);
					if (rs.getInt("LDADD") == 1)
						permissions.add(Permission.ADD);
					if (rs.getInt("LDEXPORT") == 1)
						permissions.add(Permission.EXPORT);
					if (rs.getInt("LDIMPORT") == 1)
						permissions.add(Permission.IMPORT);
					if (rs.getInt("LDDELETE") == 1)
						permissions.add(Permission.DELETE);
					if (rs.getInt("LDIMMUTABLE") == 1)
						permissions.add(Permission.IMMUTABLE);
					if (rs.getInt("LDSECURITY") == 1)
						permissions.add(Permission.SECURITY);
					if (rs.getInt("LDRENAME") == 1)
						permissions.add(Permission.RENAME);
					if (rs.getInt("LDWRITE") == 1)
						permissions.add(Permission.WRITE);
					if (rs.getInt("LDDELETE") == 1)
						permissions.add(Permission.DELETE);
					if (rs.getInt("LDSIGN") == 1)
						permissions.add(Permission.SIGN);
					if (rs.getInt("LDARCHIVE") == 1)
						permissions.add(Permission.ARCHIVE);
					if (rs.getInt("LDWORKFLOW") == 1)
						permissions.add(Permission.WORKFLOW);
					if (rs.getInt("LDDOWNLOAD") == 1)
						permissions.add(Permission.DOWNLOAD);
					if (rs.getInt("LDCALENDAR") == 1)
						permissions.add(Permission.CALENDAR);
					if (rs.getInt("LDSUBSCRIPTION") == 1)
						permissions.add(Permission.SUBSCRIPTION);
					if (rs.getInt("LDPRINT") == 1)
						permissions.add(Permission.PRINT);
					if (rs.getInt("LDPASSWORD") == 1)
						permissions.add(Permission.PASSWORD);

				}
			} finally {
				if (rs != null)
					rs.close();
				if (stmt != null)
					stmt.close();
				if (con != null)
					con.close();
			}

		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		return permissions;
	}

	@Override
	public Collection<Long> findFolderIdByUserIdAndPermission(long userId, Permission permission, Long parentId,
			boolean tree) {
		/*
		 * Important: use an HashSet because of extremely quick in existence
		 * checks.
		 */
		Set<Long> ids = new HashSet<Long>();
		try {
			User user = userDAO.findById(userId);
			if (user == null)
				return ids;

			// The administrators have all permissions on all folders
			if (user.isMemberOf("admin")) {
				if (parentId != null) {
					if (tree) {
						return findFolderIdInTree(parentId, false);
					} else {
						StringBuffer query = new StringBuffer("select ld_id from ld_folder where ld_deleted=0 ");
						query.append(" and (ld_id=" + parentId);
						query.append(" or ld_parentid=" + parentId);
						query.append(" ) ");
						return queryForList(query.toString(), Long.class);
					}
				}
			}

			/*
			 * Check folders that specify its own permissions. Here we cannot
			 * restrict to the tree since a folder in the tree can reference
			 * another folder outside.
			 */
			StringBuffer query1 = new StringBuffer("select distinct(A.ld_folderid) from ld_foldergroup A where 1=1 ");
			if (permission != Permission.READ)
				query1.append(" and A.ld_" + permission.getName() + "=1 ");

			List<Long> groupIds = user.getUserGroups().stream().map(g -> g.getGroupId()).collect(Collectors.toList());
			if (!groupIds.isEmpty()) {
				query1.append(" and A.ld_groupid in (");
				query1.append(StringUtil.arrayToString(groupIds.toArray(new Long[0]), ","));
				query1.append(") ");
			}

			List<Long> masterIds = (List<Long>) queryForList(query1.toString(), Long.class);
			if (masterIds.isEmpty())
				return ids;

			String masterIdsString = masterIds.toString().replace('[', '(').replace(']', ')');

			/*
			 * Now search for those folders that are or reference the masterIds
			 */
			StringBuffer query2 = new StringBuffer("select B.ld_id from ld_folder B where B.ld_deleted=0 ");
			query2.append(" and ( B.ld_id in " + masterIdsString);
			query2.append(" or B.ld_securityref in " + masterIdsString + ") ");

			if (parentId != null) {
				if (tree) {
					query2.append(" and B.ld_id in "
							+ findFolderIdInTree(parentId, false).toString().replace('[', '(').replace(']', ')'));
				} else {
					query2.append(" and (B.ld_id=" + parentId + " or B.ld_parentId=" + parentId + ") ");
				}
			}

			ids.addAll((List<Long>) queryForList(query2.toString(), Long.class));
		} catch (Throwable e) {
			log.error(e.getMessage(), e);
		}

		return ids;
	}

	public FolderHistoryDAO getHistoryDAO() {
		return historyDAO;
	}

	public void setHistoryDAO(FolderHistoryDAO historyDAO) {
		this.historyDAO = historyDAO;
	}

	@Override
	public void deleteAll(List<Folder> folders, FolderHistory transaction) {
		deleteAll(folders, PersistentObject.DELETED_CODE_DEFAULT, transaction);
	}

	@Override
	public void deleteAll(List<Folder> folders, int code, FolderHistory transaction) {
		for (Folder folder : folders) {
			try {
				FolderHistory deleteHistory = (FolderHistory) transaction.clone();
				deleteHistory.setEvent(FolderEvent.DELETED.toString());
				deleteHistory.setFolderId(folder.getId());
				deleteHistory.setPath(computePathExtended(folder.getId()));
				delete(folder.getId(), code, deleteHistory);
			} catch (CloneNotSupportedException e) {
				log.error(e.getMessage(), e);
			}
		}

	}

	private void checkIfCanDelete(long folderId) {
		Folder folder = findById(folderId);
		long rootId = findRoot(folder.getTenantId()).getId();
		if (folderId == rootId)
			throw new RuntimeException("You cannot delete folder " + folder.getName() + " - " + folderId);

		if (folder != null && folder.getName().equals("Default") && folder.getParentId() == rootId)
			throw new RuntimeException("You cannot delete folder " + folder.getName() + " - " + folderId);
	}

	public boolean delete(long folderId, int code) {
		checkIfCanDelete(folderId);
		return super.delete(folderId, code);
	}

	@Override
	public boolean delete(long folderId, FolderHistory transaction) {
		return delete(folderId, PersistentObject.DELETED_CODE_DEFAULT, transaction);
	}

	@Override
	public boolean delete(long folderId, int delCode, FolderHistory transaction) {
		checkIfCanDelete(folderId);
		assert (transaction.getUser() != null);

		Folder folder = findById(folderId);
		boolean result = true;
		try {
			transaction.setPath(computePathExtended(folderId));
			transaction.setEvent(FolderEvent.DELETED.toString());
			transaction.setFolderId(folderId);
			transaction.setTenantId(folder.getTenantId());

			folder.setDeleted(delCode);
			folder.setDeleteUserId(transaction.getUserId());

			store(folder, transaction);
		} catch (Throwable e) {
			if (log.isErrorEnabled())
				log.error(e.getMessage(), e);
			result = false;
		}

		return result;
	}

	@Override
	public boolean applyRithtToTree(long rootId, FolderHistory transaction) {
		assert (transaction != null);
		assert (transaction.getSessionId() != null);

		boolean result = true;

		Folder folder = findById(rootId);
		if (folder == null)
			return result;

		long securityRef = rootId;
		if (folder.getSecurityRef() != null && folder.getId() != folder.getSecurityRef())
			securityRef = folder.getSecurityRef();

		Collection<Long> treeIds = findFolderIdInTree(folder.getId(), true);
		String treeIdsString = treeIds.toString().replace('[', '(').replace(']', ')');

		int records = 0;

		try {
			/*
			 * Apply the securityRef
			 */
			records = jdbcUpdate("update ld_folder set ld_securityref = ?, ld_lastmodified = ? where not ld_id = ? "
					+ " and ld_id in " + treeIdsString, securityRef, new Date(), rootId);

			log.warn("Applied rights to " + records + " folders in tree " + rootId);

			/*
			 * Delete all the specific rights associated to the folders in the
			 * tree
			 */
			jdbcUpdate("delete from ld_foldergroup where not ld_folderid = ? and ld_folderid in " + treeIdsString,
					rootId);
			log.warn("Removed " + records + " specific rights in tree " + rootId);

			if (getSessionFactory().getCache() != null)
				getSessionFactory().getCache().evictEntityRegions();
		} catch (Throwable e) {
			result = false;
			log.error(e.getMessage(), e);
		}

		return result;
	}

	@Override
	public Folder createAlias(long parentId, long foldRef, FolderHistory transaction) {
		Folder targetFolder = findFolder(foldRef);
		assert (targetFolder != null);

		Folder parentFolder = findFolder(parentId);
		assert (parentFolder != null);

		/*
		 * Detect possible cycle
		 */
		List<Folder> parents = findParents(parentId);
		parents.add(parentFolder);
		for (Folder p : parents) {
			if (p.getId() == foldRef)
				throw new RuntimeException("Cycle detected. The alias cannot reference a parent folder");
		}

		// Prepare the transaction
		if (transaction != null) {
			transaction.setTenantId(targetFolder.getTenantId());
			transaction.setEvent(FolderEvent.CREATED.toString());
		}

		Folder folderVO = new Folder();
		folderVO.setName(targetFolder.getName());
		folderVO.setDescription(targetFolder.getDescription());
		folderVO.setTenantId(targetFolder.getTenantId());
		folderVO.setType(Folder.TYPE_ALIAS);
		if (targetFolder.getSecurityRef() != null)
			folderVO.setSecurityRef(targetFolder.getSecurityRef());
		else
			folderVO.setSecurityRef(targetFolder.getId());
		folderVO.setFoldRef(targetFolder.getId());

		// Finally create
		return create(parentFolder, folderVO, false, transaction);
	}

	@Override
	public Folder create(Folder parent, Folder folderVO, boolean inheritSecurity, FolderHistory transaction) {
		parent = findFolder(parent);

		Folder folder = new Folder();
		folder.setName(folderVO.getName());
		folder.setType(folderVO.getType());
		folder.setDescription(folderVO.getDescription());
		folder.setTenantId(parent.getTenantId());

		if (folderVO.getCreation() != null)
			folder.setCreation(folderVO.getCreation());
		if (folderVO.getCreatorId() != null)
			folder.setCreatorId(folderVO.getCreatorId());
		if (folderVO.getCreator() != null)
			folder.setCreator(folderVO.getCreator());
		folder.setParentId(parent.getId());

		setUniqueName(folder);

		folder.setTemplate(folderVO.getTemplate());
		folder.setTemplateLocked(folderVO.getTemplateLocked());
		if (folderVO.getAttributes() != null && !folderVO.getAttributes().isEmpty())
			for (String name : folderVO.getAttributes().keySet())
				folder.getAttributes().put(name, folderVO.getAttributes().get(name));

		folder.setQuotaDocs(folderVO.getQuotaDocs());
		folder.setQuotaSize(folderVO.getQuotaSize());

		if (folderVO.getFoldRef() != null) {
			folder.setFoldRef(folderVO.getFoldRef());
			folder.setSecurityRef(folderVO.getSecurityRef());
		} else if (inheritSecurity) {
			if (parent.getSecurityRef() != null)
				folder.setSecurityRef(parent.getSecurityRef());
			else
				folder.setSecurityRef(parent.getId());
		} else if (transaction != null && transaction.getUserId() != 0) {
			// At least the current user must be able to operate on the new
			// folder
			User user = userDAO.findById(transaction.getUserId());
			userDAO.initialize(user);
			if (!user.isMemberOf("admin")) {
				Group userGroup = user.getUserGroup();
				FolderGroup fg = new FolderGroup(userGroup.getId());
				fg.setAdd(1);
				fg.setDelete(1);
				fg.setDownload(1);
				fg.setPermissions(1);
				fg.setRead(1);
				fg.setSecurity(1);
				fg.setRename(1);
				fg.setWrite(1);
				folder.addFolderGroup(fg);
			}
		}

		if (transaction != null)
			transaction.setEvent(FolderEvent.CREATED.toString());

		/*
		 * Replicate the parent's metadata
		 */
		if (parent.getTemplate() != null && folderVO.getTemplate() == null && folderVO.getFoldRef() == null) {
			initialize(parent);
			folder.setTemplate(parent.getTemplate());
			try {
				for (String att : parent.getAttributeNames()) {
					Attribute ext = null;
					try {
						ext = (Attribute) parent.getAttributes().get(att).clone();
					} catch (CloneNotSupportedException e) {

					}
					folder.getAttributes().put(att, ext);
				}
			} catch (Throwable t) {
				log.warn(t.getMessage());
			}
		}

		if (store(folder, transaction) == false)
			return null;
		return folder;
	}

	@Override
	public Folder createPath(Folder parent, String path, boolean inheritSecurity, FolderHistory transaction) {
		StringTokenizer st = new StringTokenizer(path, "/", false);

		Folder folder = findFolder(parent.getId());

		while (st.hasMoreTokens()) {
			initialize(folder);

			String name = st.nextToken();

			List<Folder> childs = findByName(folder, name, folder.getTenantId(), true);
			Folder dir = null;
			if (childs.isEmpty()) {
				try {
					Folder folderVO = new Folder();
					folderVO.setName(name);
					folderVO.setType(Folder.TYPE_DEFAULT);
					dir = create(folder, folderVO, inheritSecurity,
							transaction != null ? (FolderHistory) transaction.clone() : null);
				} catch (CloneNotSupportedException e) {
				}
			} else {
				dir = childs.iterator().next();
				initialize(dir);
			}
			folder = dir;
		}
		return folder;
	}

	@Override
	public Folder findByPath(String pathExtended, long tenantId) {
		if (StringUtils.isEmpty(pathExtended))
			return null;

		StringTokenizer st = new StringTokenizer(pathExtended, "/", false);
		Folder folder = findRoot(tenantId);
		while (st.hasMoreTokens()) {
			String token = st.nextToken();
			if (StringUtils.isEmpty(token))
				continue;
			List<Folder> list = findByName(folder, token, tenantId, true);
			if (list.isEmpty()) {
				folder = null;
				break;
			}
			folder = list.get(0);
		}
		return folder;
	}

	private void setUniqueName(Folder folder) {
		int counter = 1;

		String folderName = folder.getName();

		List<String> collisions = (List<String>) queryForList(
				"select lower(ld_name) from ld_folder where ld_deleted=0 and ld_parentid=" + folder.getParentId()
						+ " and lower(ld_name) like'" + SqlUtil.doubleQuotes(folderName.toLowerCase()) + "%'",
				String.class);
		while (collisions.contains(folder.getName().toLowerCase()))
			folder.setName(folderName + "(" + (counter++) + ")");
	}

	@Override
	public void copy(Folder source, Folder target, boolean foldersOnly, boolean inheritSecurity,
			FolderHistory transaction) throws Exception {
		assert (source != null);
		assert (target != null);
		assert (transaction != null);
		assert (transaction.getUser() != null);

		target = findFolder(target);

		if (isInPath(source.getId(), target.getId()))
			throw new IllegalArgumentException("Cannot copy a folder inside the same path");

		// Create the same folder in the target
		Folder newFolder = createPath(target, source.getName(), inheritSecurity, (FolderHistory) transaction.clone());
		newFolder.setFoldRef(source.getFoldRef());

		DocumentDAO docDao = (DocumentDAO) Context.get().getBean(DocumentDAO.class);
		DocumentManager docMan = (DocumentManager) Context.get().getBean(DocumentManager.class);

		// List source docs and create them in the new folder
		if (!foldersOnly) {
			List<Document> srcDocs = docDao.findByFolder(source.getId(), null);
			for (Document srcDoc : srcDocs) {
				docDao.initialize(srcDoc);
				Document newDoc = (Document) srcDoc.clone();
				newDoc.setId(0L);
				newDoc.setCustomId(null);
				newDoc.setVersion(null);
				newDoc.setFileVersion(null);
				newDoc.setFolder(newFolder);
				newDoc.setIndexed(0);
				newDoc.setStatus(Document.DOC_UNLOCKED);
				newDoc.setImmutable(0);
				newDoc.setBarcoded(0);
				newDoc.setRating(0);

				History documentTransaction = new History();
				documentTransaction.setSessionId(transaction.getSessionId());
				documentTransaction.setUser(transaction.getUser());
				documentTransaction.setUserId(transaction.getUserId());
				documentTransaction.setUsername(transaction.getUsername());
				documentTransaction.setComment(transaction.getComment());
				documentTransaction.setEvent(DocumentEvent.STORED.toString());

				String oldDocResource = storer.getResourceName(srcDoc, null, null);
				InputStream is = null;
				try {
					is = storer.getStream(srcDoc.getId(), oldDocResource);
					docMan.create(is, newDoc, documentTransaction);
				} catch (Throwable t) {
					log.error(t.getMessage(), t);
					if (is != null)
						is.close();
				}
			}
		}

		List<Folder> children = findChildren(source.getId(), transaction.getUser().getId());
		for (Folder child : children)
			copy(child, newFolder, foldersOnly, inheritSecurity, transaction);
	}

	@Override
	public void move(Folder source, Folder target, FolderHistory transaction) throws Exception {
		assert (source != null);
		assert (target != null);
		assert (transaction != null);
		assert (transaction.getUser() != null);

		Folder targetFolder = findFolder(target.getId());
		initialize(targetFolder);

		if (isInPath(source.getId(), targetFolder.getId()))
			throw new IllegalArgumentException("Cannot move a folder inside the same path");

		initialize(source);

		long oldParent = source.getParentId();
		String pathOld = computePathExtended(source.getId());
		transaction.setPathOld(pathOld);

		// Change the parent folder
		source.setParentId(targetFolder.getId());

		// Ensure unique folder name in a folder
		setUniqueName(source);

		// Modify folder history entry
		transaction.setEvent(FolderEvent.MOVED.toString());

		store(source, transaction);

		/*
		 * Now save the event in the parent folder
		 */
		FolderHistory hist = new FolderHistory();
		hist.setFolderId(oldParent);
		hist.setEvent(FolderEvent.SUBFOLDER_MOVED.toString());
		hist.setSessionId(transaction.getSessionId());
		hist.setUserId(transaction.getUserId());
		hist.setUsername(transaction.getUsername());
		hist.setFilename(source.getName());
		hist.setPath(transaction.getPath());
		hist.setPathOld(transaction.getPathOld());

		historyDAO.store(hist);
	}

	@Override
	public List<Folder> deleteTree(long folderId, FolderHistory transaction) throws Exception {
		return deleteTree(folderId, PersistentObject.DELETED_CODE_DEFAULT, transaction);
	}

	@Override
	public List<Folder> deleteTree(long folderId, int delCode, FolderHistory transaction) throws Exception {
		List<Folder> notDeleted = deleteTree(findById(folderId), delCode, transaction);
		return notDeleted;
	}

	@Override
	public List<Folder> deleteTree(Folder folder, int delCode, FolderHistory transaction) throws Exception {
		assert (delCode != 0);
		assert (folder != null);
		assert (transaction != null);
		assert (transaction.getUser() != null);

		// If the folder just an alias just delete it
		if (folder.getType() == Folder.TYPE_ALIAS) {
			delete(folder.getId(), delCode, transaction);
			return new ArrayList<Folder>();
		}

		List<Folder> notDeletableFolders = new ArrayList<Folder>();

		Collection<Long> treeIds = findFolderIdInTree(folder.getId(), true);
		String treeIdsString = treeIds.toString().replace('[', '(').replace(']', ')');

		/*
		 * Check if in the folders to be deleted there is at least one immutable
		 * document
		 */
		List<Long> ids = (List<Long>) queryForList("select A.ld_folderid from ld_document A "
				+ " where A.ld_deleted=0 and A.ld_immutable=1 and A.ld_folderid in " + treeIdsString, Long.class);
		if (ids != null && !ids.isEmpty()) {
			log.warn("Found undeletable documents in tree " + folder.getName() + " - " + folder.getId());
			for (Long id : ids)
				notDeletableFolders.add(findById(id));
			return notDeletableFolders;
		}

		delete(folder.getId(), delCode, transaction);

		/*
		 * Mark as deleted all the folders
		 */
		int records = jdbcUpdate("update ld_folder set ld_deleted=" + delCode + " where not ld_id=" + folder.getId()
				+ " and ld_id in " + treeIdsString);

		/*
		 * Delete the documents as well
		 */
		jdbcUpdate("update ld_document set ld_deleted=" + delCode + " where ld_folderid in " + treeIdsString);

		if (getSessionFactory().getCache() != null)
			getSessionFactory().getCache().evictEntityRegions();
		getSessionFactory().getCache().evictCollectionRegions();

		log.warn("Deleted " + records + " folders in tree " + folder.getName() + " - " + folder.getId());

		return notDeletableFolders;
	}

	@Override
	public Set<Long> findFolderIdInTree(long rootId, boolean includeDeleted) {
		Set<Long> ids = new HashSet<Long>();
		ids.add(rootId);

		List<Long> lastIds = new ArrayList<Long>();
		lastIds.add(rootId);
		while (!lastIds.isEmpty()) {
			String idsString = ids.toString().replace('[', '(').replace(']', ')');

			lastIds.clear();
			lastIds = queryForList("select A.ld_id from ld_folder A where "
					+ (includeDeleted ? "" : " A.ld_deleted=0 and ") + " A.ld_id not in " + idsString
					+ " and A.ld_parentid in " + idsString, Long.class);
			if (!lastIds.isEmpty())
				ids.addAll(lastIds);
		}

		return ids;
	}

	@Override
	public List<Folder> find(String name, Long tenantId) {
		return findByName(null, "%" + name + "%", tenantId, false);
	}

	@Override
	public boolean isInPath(long folderId, long targetId) {
		for (Folder folder : findParents(targetId)) {
			if (folder.getId() == folderId)
				return true;
		}
		return false;
	}

	@Override
	public int count(boolean computeDeleted) {
		return queryForInt("SELECT COUNT(A.ld_id) FROM ld_document A "
				+ (computeDeleted ? "" : "where A.ld_deleted = 0 "));
	}

	@Override
	public List<Folder> findWorkspaces(long tenantId) {
		Folder root = findRoot(tenantId);
		if (root == null)
			return new ArrayList<Folder>();
		long rootId = root.getId();
		return findByWhere(" (not _entity.id=" + rootId + ") and _entity.parentId=" + rootId + " and _entity.type="
				+ Folder.TYPE_WORKSPACE + " and _entity.tenantId=" + tenantId, "order by lower(_entity.name)", null);
	}

	@Override
	public void initialize(Folder folder) {
		try {
			refresh(folder);

			if (folder.getFolderGroups() != null)
				folder.getFolderGroups().size();

			if (folder.getTags() != null)
				folder.getTags().size();

			for (String attribute : folder.getAttributes().keySet()) {
				folder.getAttributes().get(attribute).getValue();
			}
		} catch (Throwable t) {
		}
	}

	@Override
	public List<Folder> findDeleted(long userId, Integer maxHits) {
		List<Folder> results = new ArrayList<Folder>();
		try {
			String query = "select ld_id, ld_name, ld_lastmodified from ld_folder where ld_deleted=1 and ld_deleteuserid = "
					+ userId + " order by ld_lastmodified desc";

			@SuppressWarnings("rawtypes")
			RowMapper mapper = new BeanPropertyRowMapper() {
				public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
					Folder fld = new Folder();
					fld.setId(rs.getLong(1));
					fld.setName(rs.getString(2));
					fld.setLastModified(rs.getTimestamp(3));
					return fld;
				}
			};

			results = (List<Folder>) query(query, null, mapper, maxHits);
		} catch (Exception e) {
			log.error(e.getMessage());
		}

		return results;
	}

	@Override
	public Folder findRoot(long tenantId) {
		List<Folder> folders = findByName("/", tenantId);
		if (!folders.isEmpty())
			return folders.get(0);
		return null;
	}

	@Override
	public Folder findDefaultWorkspace(long tenantId) {
		Folder root = findRoot(tenantId);
		if (root == null)
			return null;

		List<Folder> workspaces = findByWhere(
				"_entity.parentId = " + root.getId() + " and _entity.name = '"
						+ SqlUtil.doubleQuotes(Folder.DEFAULTWORKSPACENAME) + "' and _entity.tenantId=" + tenantId
						+ " and _entity.type=" + Folder.TYPE_WORKSPACE, null, null);

		if (workspaces.isEmpty())
			return null;
		else
			return workspaces.get(0);
	}

	public void setStorer(Storer storer) {
		this.storer = storer;
	}

	@Override
	public boolean updateSecurityRef(long folderId, long rightsFolderId, FolderHistory transaction) {
		boolean result = true;
		try {
			Folder f = findById(folderId);
			initialize(f);

			Folder rightsFolder = findById(rightsFolderId);
			long securityRef = rightsFolderId;
			if (rightsFolder.getSecurityRef() != null)
				securityRef = rightsFolder.getSecurityRef();

			if (transaction != null)
				transaction.setEvent(FolderEvent.PERMISSION.toString());

			f.setSecurityRef(securityRef);
			if (!store(f, transaction))
				return false;

			// Now all the folders that are referencing this one must be updated
			bulkUpdate("set securityRef=" + securityRef + " where securityRef=" + folderId, null);
		} catch (Throwable e) {
			result = false;
			log.error(e.getMessage(), e);
		}

		return result;
	}

	@Override
	public long countDocsInTree(long rootId) {
		Collection<Long> folderIds = findFolderIdInTree(rootId, false);

		String query = "select count(*) from ld_document where ld_deleted = 0 ";
		query += " and ld_folderid in " + folderIds.toString().replace('[', '(').replace(']', ')');

		return queryForLong(query);
	}

	@Override
	public long computeTreeSize(long rootId) {
		Collection<Long> folderIds = findFolderIdInTree(rootId, false);

		String query = "select sum(A.ld_filesize) from ld_version A where A.ld_deleted = 0 and A.ld_version = A.ld_fileversion ";
		query += " and A.ld_folderid in " + folderIds.toString().replace('[', '(').replace(']', ')');

		return queryForLong(query);
	}

	@Override
	public List<Folder> findAliases(Long foldRef, long tenantId) {
		String query = " _entity.tenantId=" + tenantId;
		if (foldRef != null)
			query += " and _entity.foldRef=" + foldRef;
		return findByWhere(query, null, null);
	}

	@Override
	public Folder findFolder(long folderId) {
		Folder f = findById(folderId);
		try {
			if (f != null && f.getFoldRef() != null)
				f = findById(f.getFoldRef());
		} catch (Throwable t) {
			log.warn(t.getMessage());

		}
		return f;
	}

	private Folder findFolder(Folder folder) {
		try {
			if (folder.getFoldRef() != null)
				return findById(folder.getFoldRef());
		} catch (Throwable t) {
			log.warn(t.getMessage());
		}
		return folder;
	}

	@Override
	public boolean applyMetadataToTree(long id, FolderHistory transaction) {
		boolean result = true;

		Folder parent = findById(id);
		if (parent == null)
			return result;

		try {
			initialize(parent);
			transaction.setEvent(FolderEvent.CHANGED.toString());
			transaction.setTenantId(parent.getTenantId());
			transaction.setNotifyEvent(false);

			// Iterate over all children setting the template and field values
			List<Folder> children = findChildren(id, null);
			for (Folder folder : children) {
				initialize(folder);

				FolderHistory tr = (FolderHistory) transaction.clone();
				tr.setFolderId(folder.getId());

				folder.setTemplate(parent.getTemplate());
				folder.setTemplateLocked(parent.getTemplateLocked());
				for (String name : parent.getAttributeNames()) {
					Attribute ext = (Attribute) parent.getAttributes().get(name).clone();
					folder.getAttributes().put(name, ext);
				}

				store(folder, tr);
				flush();

				if (!applyMetadataToTree(folder.getId(), transaction))
					return false;
			}
		} catch (Throwable e) {
			if (log.isErrorEnabled())
				log.error(e.getMessage(), e);
			result = false;
		}
		return result;
	}

	@Override
	public boolean applyTagsToTree(long id, FolderHistory transaction) {
		boolean result = true;

		Folder parent = findById(id);
		if (parent == null)
			return result;

		try {
			initialize(parent);
			transaction.setEvent(FolderEvent.CHANGED.toString());
			transaction.setTenantId(parent.getTenantId());
			transaction.setNotifyEvent(false);

			// Iterate over all children setting the template and field values
			List<Folder> children = findChildren(id, null);
			for (Folder folder : children) {
				initialize(folder);

				FolderHistory tr = (FolderHistory) transaction.clone();
				tr.setFolderId(folder.getId());

				if (folder.getTags() != null)
					folder.getTags().clear();
				if (parent.getTags() != null)
					for (Tag tag : parent.getTags())
						folder.addTag(tag.getTag());

				store(folder, tr);
				flush();

				if (!applyTagsToTree(folder.getId(), transaction))
					return false;
			}
		} catch (Throwable e) {
			if (log.isErrorEnabled())
				log.error(e.getMessage(), e);
			result = false;
		}
		return result;
	}

	public List<Long> findFolderIdByTag(String tag) {
		StringBuilder query = new StringBuilder(
				"select distinct(A.ld_folderid) from ld_foldertag A, ld_folder B where A.ld_folderid=B.ld_id and B.ld_deleted= 0");
		query.append(" and lower(ld_tag)='" + SqlUtil.doubleQuotes(tag).toLowerCase() + "'");
		return (List<Long>) queryForList(query.toString(), Long.class);
	}

	public List<Long> findFolderIdByUserIdAndTag(long userId, String tag) {
		List<Long> ids = new ArrayList<Long>();
		try {
			User user = userDAO.findById(userId);
			if (user == null)
				return ids;

			StringBuffer query = new StringBuffer();

			if (user.isMemberOf("admin")) {
				ids = findFolderIdByTag(tag);
			} else {

				/*
				 * Search for all accessible folders
				 */
				Collection<Long> precoll = findFolderIdByUserId(userId, null, true);
				String precollString = precoll.toString().replace('[', '(').replace(']', ')');

				query.append("select distinct(C.ld_id) from ld_folder C, ld_foldertag D "
						+ " where C.ld_id=D.ld_folderid AND C.ld_deleted=0 ");
				query.append(" AND C.ld_folderid in ");
				query.append(precollString);
				query.append(" AND D.ld_tag='" + SqlUtil.doubleQuotes(tag.toLowerCase()) + "' ");

				List<Long> docIds = (List<Long>) queryForList(query.toString(), Long.class);
				ids.addAll(docIds);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		return ids;
	}

	@Override
	public List<Folder> findByUserIdAndTag(long userId, String tag, Integer max) {
		List<Folder> coll = new ArrayList<Folder>();

		Collection<Long> ids = findFolderIdByUserIdAndTag(userId, tag);
		StringBuffer buf = new StringBuffer();
		if (!ids.isEmpty()) {
			boolean first = true;
			for (Long id : ids) {
				if (!first)
					buf.append(",");
				buf.append(id);
				first = false;
			}

			StringBuffer query = new StringBuffer("select A from Folder A where A.id in (");
			query.append(buf);
			query.append(")");
			coll = (List<Folder>) findByQuery(query.toString(), null, max);
		}
		return coll;
	}
}