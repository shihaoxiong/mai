"""Test if ChromaDB tries to download embedding model"""
import chromadb
from chromadb.config import Settings as ChromaSettings
import tempfile
import os

print("Creating ChromaDB collection WITHOUT embedding function...")

with tempfile.TemporaryDirectory() as tmpdir:
    client = chromadb.PersistentClient(
        path=tmpdir,
        settings=ChromaSettings(
            anonymized_telemetry=False,
            allow_reset=True
        )
    )
    
    print(f"Creating collection in {tmpdir}")
    collection = client.create_collection(
        name="test_collection",
        embedding_function=None,  # Explicitly disable embedding
        metadata={"test": "true"}
    )
    
    print("Collection created successfully!")
    print(f"Collection name: {collection.name}")
    print(f"No embedding model should have been downloaded.")
    
    # Check if any model files were downloaded
    chroma_cache = os.path.expanduser("~/.cache/chroma/onnx_models")
    if os.path.exists(chroma_cache):
        print(f"\nCache location: {chroma_cache}")
        for root, dirs, files in os.walk(chroma_cache):
            for f in files:
                fpath = os.path.join(root, f)
                print(f"  - {fpath}")
    else:
        print(f"\nNo ChromaDB cache found at {chroma_cache}")

print("\nTest completed!")
