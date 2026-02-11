"""Long-term Memory Implementation

Persistent memory using ChromaDB for semantic storage.
"""

import asyncio
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

import chromadb
from chromadb.config import Settings as ChromaSettings
from chromadb import EmbeddingFunction
from chromadb.errors import NotFoundError as ChromaNotFoundError

from .base import LongTermMemoryProtocol, Memory, MemoryType
from ...llm import MiniMaxEmbeddingProvider
from ...logger import get_logger


logger = get_logger("long_term_memory")


class LongTermMemory(LongTermMemoryProtocol):
    """Long-term memory implementation using ChromaDB

    Stores persistent knowledge for cross-task reuse.
    Uses MiniMax embeddings for semantic search.
    """

    def __init__(
        self,
        chroma_path: str = "./data/chroma",
        collection_name: str = "agent_memories",
        embedding_provider: Optional[MiniMaxEmbeddingProvider] = None,
        embedding_api_key: Optional[str] = None
    ):
        self.chroma_path = Path(chroma_path)
        self.collection_name = collection_name
        self._embedding_provider = embedding_provider
        self._embedding_api_key = embedding_api_key
        self._client: Optional[chromadb.Client] = None
        self._collection: Optional[chromadb.Collection] = None
        self._lock = asyncio.Lock()

    async def initialize(self) -> None:
        """Initialize ChromaDB client and collection"""
        async with self._lock:
            self.chroma_path.mkdir(parents=True, exist_ok=True)

            self._client = chromadb.PersistentClient(
                path=str(self.chroma_path),
                settings=ChromaSettings(
                    anonymized_telemetry=False,
                    allow_reset=True
                )
            )

            try:
                self._collection = self._client.get_collection(
                    name=self.collection_name
                )
                logger.info(
                    "Loaded existing collection",
                    collection=self.collection_name,
                    count=self._collection.count()
                )
            except ChromaNotFoundError:
                self._collection = self._client.create_collection(
                    name=self.collection_name,
                    embedding_function=None,
                    metadata={"description": "MAI Agent long-term memory"}
                )
                logger.info(
                    "Created new collection",
                    collection=self.collection_name
                )

            logger.info(
                "Long-term memory initialized",
                path=str(self.chroma_path),
                collection=self.collection_name
            )

    def _get_embedding_function(self) -> Optional[EmbeddingFunction]:
        """Get embedding function for ChromaDB"""
        if self._embedding_api_key and self._embedding_provider is None:
            self._embedding_provider = MiniMaxEmbeddingProvider(
                api_key=self._embedding_api_key
            )

        if self._embedding_provider is None:
            return None

        return None

    async def add(
        self,
        content: str,
        metadata: Optional[Dict[str, Any]] = None,
        importance_score: float = 0.0
    ) -> str:
        """Add a memory entry"""
        async with self._lock:
            if self._collection is None:
                raise RuntimeError("Long-term memory not initialized")

            memory_id = str(uuid.uuid4())

            embedding = None
            if self._embedding_provider:
                try:
                    embedding = await self._embedding_provider.embed_query(content)
                except Exception as e:
                    logger.warning(
                        "Failed to generate embedding",
                        error=str(e)
                    )

            document = content

            metadata_dict = metadata or {}
            metadata_dict.update({
                "created_at": datetime.now(timezone.utc).isoformat(),
                "importance_score": importance_score,
                "content_length": len(content)
            })

            self._collection.add(
                documents=[document],
                embeddings=[embedding] if embedding else None,
                ids=[memory_id],
                metadatas=[metadata_dict]
            )

            logger.debug(
                "Memory added",
                memory_id=memory_id,
                content_length=len(content)
            )

            return memory_id

    async def search(
        self,
        query: str,
        limit: int = 5,
        filter_metadata: Optional[Dict[str, Any]] = None,
        min_importance: Optional[float] = None
    ) -> List[Dict[str, Any]]:
        """Search memories by semantic similarity"""
        async with self._lock:
            if self._collection is None:
                raise RuntimeError("Long-term memory not initialized")

            embedding = None
            if self._embedding_provider:
                try:
                    embedding = await self._embedding_provider.embed_query(query)
                except Exception as e:
                    logger.warning(
                        "Failed to generate query embedding",
                        error=str(e)
                    )

            where_clause = {}
            if filter_metadata:
                for key, value in filter_metadata.items():
                    where_clause[key] = value

            if min_importance is not None:
                where_clause["importance_score"] = {"$gte": min_importance}

            try:
                if embedding:
                    results = self._collection.query(
                        query_embeddings=[embedding],
                        n_results=limit,
                        where=where_clause if where_clause else None,
                        include=["documents", "metadatas", "distances"]
                    )
                else:
                    results = self._collection.query(
                        query_texts=[query],
                        n_results=limit,
                        where=where_clause if where_clause else None,
                        include=["documents", "metadatas", "distances"]
                    )
            except Exception as e:
                logger.error(
                    "Memory search failed",
                    error=str(e)
                )
                return []

            memories = []
            if results.get("ids") and results["ids"][0]:
                for i, memory_id in enumerate(results["ids"][0]):
                    memory = {
                        "id": memory_id,
                        "content": results["documents"][0][i] if results["documents"] else "",
                        "metadata": results["metadatas"][0][i] if results["metadatas"] else {},
                        "distance": results["distances"][0][i] if results["distances"] else None,
                        "relevance_score": 1.0 - (results["distances"][0][i] / 2.0) if results["distances"] else None
                    }
                    memories.append(memory)

            logger.debug(
                "Memory search completed",
                query=query,
                results_count=len(memories)
            )

            return memories

    async def get(self, memory_id: str) -> Optional[Dict[str, Any]]:
        """Get a memory entry by ID"""
        async with self._lock:
            if self._collection is None:
                raise RuntimeError("Long-term memory not initialized")

            try:
                result = self._collection.get(
                    ids=[memory_id],
                    include=["documents", "metadatas"]
                )

                if not result["ids"]:
                    return None

                return {
                    "id": result["ids"][0],
                    "content": result["documents"][0] if result["documents"] else "",
                    "metadata": result["metadatas"][0] if result["metadatas"] else {}
                }
            except Exception as e:
                logger.error(
                    "Failed to get memory",
                    memory_id=memory_id,
                    error=str(e)
                )
                return None

    async def delete(self, memory_id: str) -> bool:
        """Delete a memory entry"""
        async with self._lock:
            if self._collection is None:
                raise RuntimeError("Long-term memory not initialized")

            try:
                self._collection.delete(ids=[memory_id])
                logger.debug(
                    "Memory deleted",
                    memory_id=memory_id
                )
                return True
            except Exception as e:
                logger.error(
                    "Failed to delete memory",
                    memory_id=memory_id,
                    error=str(e)
                )
                return False

    async def update(
        self,
        memory_id: str,
        content: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None
    ) -> bool:
        """Update a memory entry"""
        async with self._lock:
            if self._collection is None:
                raise RuntimeError("Long-term memory not initialized")

            try:
                if content:
                    self._collection.update(
                        ids=[memory_id],
                        documents=[content]
                    )

                if metadata:
                    self._collection.update(
                        ids=[memory_id],
                        metadatas=[metadata]
                    )

                logger.debug(
                    "Memory updated",
                    memory_id=memory_id
                )
                return True
            except Exception as e:
                logger.error(
                    "Failed to update memory",
                    memory_id=memory_id,
                    error=str(e)
                )
                return False

    async def clear(self) -> None:
        """Clear all memories"""
        async with self._lock:
            if self._client is None:
                return

            try:
                self._client.delete_collection(name=self.collection_name)
                self._collection = None

                self._collection = self._client.create_collection(
                    name=self.collection_name,
                    embedding_function=None,
                    metadata={"description": "MAI Agent long-term memory"}
                )

                logger.info("Long-term memory cleared")
            except Exception as e:
                logger.error(
                    "Failed to clear memory",
                    error=str(e)
                )

    async def count(self) -> int:
        """Get total memory count"""
        async with self._lock:
            if self._collection is None:
                return 0

            return self._collection.count()

    async def get_stats(self) -> Dict[str, Any]:
        """Get memory statistics"""
        count = await self.count()

        memories = []
        if count > 0:
            try:
                result = self._collection.get(include=["metadatas"], limit=min(count, 1000))
                memories = result.get("metadatas", [])
            except Exception:
                pass

        importance_scores = [
            m.get("importance_score", 0) for m in memories if m
        ]

        return {
            "total_memories": count,
            "avg_importance": sum(importance_scores) / len(importance_scores) if importance_scores else 0,
            "collection_name": self.collection_name,
            "storage_path": str(self.chroma_path)
        }

    async def close(self) -> None:
        """Close ChromaDB client"""
        if self._embedding_provider:
            await self._embedding_provider.close()
            self._embedding_provider = None

        self._collection = None
        self._client = None

        logger.info("Long-term memory closed")


class InMemoryLongTermMemory(LongTermMemoryProtocol):
    """Simple in-memory implementation for development/testing"""

    def __init__(self):
        self._memories: Dict[str, Dict[str, Any]] = {}
        self._lock = asyncio.Lock()

    async def add(
        self,
        content: str,
        metadata: Optional[Dict[str, Any]] = None
    ) -> str:
        async with self._lock:
            memory_id = str(uuid.uuid4())
            self._memories[memory_id] = {
                "id": memory_id,
                "content": content,
                "metadata": metadata or {},
                "created_at": datetime.now(timezone.utc).isoformat()
            }
            return memory_id

    async def search(
        self,
        query: str,
        limit: int = 5
    ) -> List[Dict[str, Any]]:
        async with self._lock:
            results = [
                {**mem, "relevance_score": 1.0}
                for mem in self._memories.values()
            ]
            return results[:limit]

    async def get(self, memory_id: str) -> Optional[Dict[str, Any]]:
        async with self._lock:
            return self._memories.get(memory_id)

    async def delete(self, memory_id: str) -> bool:
        async with self._lock:
            if memory_id in self._memories:
                del self._memories[memory_id]
                return True
            return False

    async def clear(self) -> None:
        async with self._lock:
            self._memories.clear()

    async def close(self) -> None:
        pass
