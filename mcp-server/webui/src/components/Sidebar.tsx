import { useEffect, useState } from "react";
import { FileNode, fetchChildren, fetchPath } from "../api";
import { FolderIcon, ChevronRightIcon } from "../icons";

interface SidebarProps {
  rootId: string;
  currentId: string;
  onSelect: (node: FileNode) => void;
}

interface TreeNode {
  node: FileNode;
  expanded: boolean;
  loading: boolean;
  children: FileNode[];
  loaded: boolean;
}

export function Sidebar({ rootId, currentId, onSelect }: SidebarProps) {
  const [tree, setTree] = useState<Map<string, TreeNode>>(new Map());

  const expand = async (id: string) => {
    setTree((prev) => {
      const next = new Map(prev);
      const existing = next.get(id);
      if (existing) {
        next.set(id, { ...existing, expanded: true });
      } else {
        next.set(id, {
          node: { id, parentId: null, name: "", type: "FOLDER", size: 0,
            mimeType: null, owner: "", visibility: "", createdAt: "",
            updatedAt: "", version: 0 } as FileNode,
          expanded: true, loading: true, children: [], loaded: false,
        });
      }
      return next;
    });

    const children = await fetchChildren(id);
    setTree((prev) => {
      const next = new Map(prev);
      const existing = next.get(id)!;
      next.set(id, {
        ...existing,
        node: existing.node.name === "" && id === rootId
          ? { ...existing.node, name: "My files" }
          : existing.node,
        children: children.filter((c) => c.type === "FOLDER"),
        loading: false,
        loaded: true,
        expanded: true,
      });
      return next;
    });
  };

  const collapse = (id: string) => {
    setTree((prev) => {
      const next = new Map(prev);
      const existing = next.get(id);
      if (existing) next.set(id, { ...existing, expanded: false });
      return next;
    });
  };

  // Expand the root on mount.
  useEffect(() => {
    expand(rootId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rootId]);

  // When the current selection changes, expand its ancestors so it's visible.
  useEffect(() => {
    let active = true;
    (async () => {
      const p = await fetchPath(currentId).catch(() => [] as FileNode[]);
      if (!active) return;
      for (const node of p) await expand(node.id);
    })();
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentId]);

  const renderNode = (node: FileNode, depth: number): React.ReactNode => {
    const state = tree.get(node.id);
    const expanded = state?.expanded ?? false;
    const isActive = node.id === currentId;
    const childFolders = state?.children ?? [];

    return (
      <li key={node.id}>
        <div
          className={`tree-row ${isActive ? "is-active" : ""}`}
          style={{ paddingLeft: `calc(${depth} * 14px + var(--space-2xs))` }}
          onClick={() => onSelect(node)}
        >
          <button
            className="tree-chevron"
            onClick={(e) => {
              e.stopPropagation();
              expanded ? collapse(node.id) : expand(node.id);
            }}
            aria-label={expanded ? "Collapse" : "Expand"}
          >
            <ChevronRightIcon
              size={14}
              className={expanded ? "chev-open" : ""}
            />
          </button>
          <FolderIcon
            size={15}
            className={isActive ? "tree-icon-active" : "tree-icon"}
          />
          <span className="tree-name">{node.name}</span>
        </div>
        {expanded && childFolders.length > 0 && (
          <ul className="tree-children">
            {childFolders.map((child) => renderNode(child, depth + 1))}
          </ul>
        )}
      </li>
    );
  };

  const rootNode = tree.get(rootId)?.node ?? null;

  return (
    <aside className="sidebar">
      <div className="sidebar-section-label">Folders</div>
      {rootNode && (
        <ul className="tree">{renderNode(rootNode, 0)}</ul>
      )}
    </aside>
  );
}
