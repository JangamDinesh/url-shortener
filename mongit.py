#!/usr/bin/env python3
"""
mongit.py - A minimal Git-like sync tool using MongoDB as storage

Usage:
    python mongit.py push
    python mongit.py pull
    python mongit.py list

Place this file in the parent folder of your project. It will automatically:
- Use the parent folder name as the project name
- Sync all files recursively in the parent folder
- Respect .gitignore patterns
"""

import os
import sys
import argparse
import hashlib
import base64
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional, List, Dict, Any, Set
try:
    from pymongo import MongoClient, ASCENDING
    from pymongo.errors import ConnectionFailure, OperationFailure
except ImportError:
    print("Error: pymongo is not installed. Please run: pip install pymongo")
    sys.exit(1)

# ============================================================================
# CONFIGURATION - Edit this section with your MongoDB connection details
# ============================================================================
MONGO_URI = "mongodb+srv://username:password@cluster0.a7t4rzz.mongodb.net/?retryWrites=true&w=majority&appName=Cluster0"  # Change this to your MongoDB URI
# ============================================================================


class MongitError(Exception):
    """Base exception for mongit errors"""
    pass


class GitIgnoreParser:
    """Parser for .gitignore patterns"""
    
    def __init__(self, root_path: Path):
        """
        Initialize GitIgnore parser
        
        Args:
            root_path: Root directory to search for .gitignore files
        """
        self.root_path = root_path
        self.patterns = []
        self._load_gitignore()
    
    def _load_gitignore(self):
        """Load and parse .gitignore file if it exists"""
        gitignore_path = self.root_path / '.gitignore'
        if not gitignore_path.exists():
            return
        
        try:
            with open(gitignore_path, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    # Skip empty lines and comments
                    if not line or line.startswith('#'):
                        continue
                    self.patterns.append(line)
        except Exception as e:
            print(f"Warning: Could not read .gitignore: {e}")
    
    def _match_pattern(self, path: str, pattern: str) -> bool:
        """
        Check if a path matches a gitignore pattern
        
        Args:
            path: Relative path to check (with forward slashes)
            pattern: Gitignore pattern
            
        Returns:
            True if path matches pattern
        """
        # Handle negation patterns (not implemented for simplicity)
        if pattern.startswith('!'):
            return False
        
        # Remove leading slash
        if pattern.startswith('/'):
            pattern = pattern[1:]
        
        # Convert gitignore pattern to regex
        # Escape special regex characters except * and ?
        pattern = pattern.replace('.', r'\.')
        pattern = pattern.replace('+', r'\+')
        pattern = pattern.replace('^', r'\^')
        pattern = pattern.replace('$', r'\$')
        pattern = pattern.replace('(', r'\(')
        pattern = pattern.replace(')', r'\)')
        pattern = pattern.replace('[', r'\[')
        pattern = pattern.replace(']', r'\]')
        pattern = pattern.replace('{', r'\{')
        pattern = pattern.replace('}', r'\}')
        pattern = pattern.replace('|', r'\|')
        
        # Handle ** (match any number of directories)
        pattern = pattern.replace('**/', '(.*/)?' )
        pattern = pattern.replace('**', '.*')
        
        # Handle * (match anything except /)
        pattern = pattern.replace('*', '[^/]*')
        
        # Handle ? (match single character except /)
        pattern = pattern.replace('?', '[^/]')
        
        # If pattern ends with /, it matches directories
        if pattern.endswith('/'):
            pattern = pattern[:-1] + '(/.*)?$'
        else:
            # Check if it's a directory pattern or file pattern
            if '/' not in pattern:
                # Match in any directory
                pattern = f'(.*/)?' + pattern + '$'
            else:
                pattern = pattern + '$'
        
        # Match from start
        if not pattern.startswith('('):
            pattern = '^' + pattern
        else:
            pattern = '^' + pattern
        
        try:
            return bool(re.match(pattern, path))
        except re.error:
            # If regex fails, do simple substring match
            return pattern in path
    
    def should_ignore(self, path: Path) -> bool:
        """
        Check if a path should be ignored based on .gitignore patterns
        
        Args:
            path: Absolute path to check
            
        Returns:
            True if path should be ignored
        """
        try:
            relative_path = path.relative_to(self.root_path).as_posix()
        except ValueError:
            return False
        
        # Get path parts and filename
        parts = Path(relative_path).parts
        filename = path.name
        
        # Always ignore .git directory
        if '.git' in parts:
            return True
        
        # Always ignore Python cache files and directories (hardcoded)
        if '__pycache__' in parts:
            return True
        if filename.endswith('.pyc'):
            return True
        if filename.endswith('.pyo'):
            return True
        if filename.endswith('.pyd'):
            return True
        if filename.endswith('.so'):
            return True
        
        # Always ignore common build/cache directories
        ignored_dirs = {'__pycache__', '.pytest_cache', '.mypy_cache', 
                       '.tox', '.coverage', 'htmlcov', 'dist', 'build',
                       '.eggs', '*.egg-info', 'node_modules'}
        for ignored_dir in ignored_dirs:
            if ignored_dir in parts:
                return True
        
        # Check against gitignore patterns
        for pattern in self.patterns:
            if self._match_pattern(relative_path, pattern):
                return True
        
        return False


class Mongit:
    """Main class for mongit operations"""
    
    def __init__(self, mongo_uri: str, project: str, root_path: Path):
        """
        Initialize Mongit with MongoDB connection and project name
        
        Args:
            mongo_uri: MongoDB connection string
            project: Project name
            root_path: Root directory path
        """
        self.project = project
        self.mongo_uri = mongo_uri
        self.root_path = root_path
        self.client = None
        self.db = None
        self.collection = None
        self.gitignore = GitIgnoreParser(root_path)
        
    def connect(self):
        """Establish connection to MongoDB"""
        try:
            self.client = MongoClient(self.mongo_uri, serverSelectionTimeoutMS=5000)
            # Test connection
            self.client.admin.command('ping')
            self.db = self.client[self.project]
            self.collection = self.db['files']
            # Create index for efficient queries
            self.collection.create_index([
                ('project', ASCENDING),
                ('file_path', ASCENDING),
                ('version', ASCENDING)
            ])
            print(f"✓ Connected to MongoDB")
        except ConnectionFailure as e:
            raise MongitError(f"Failed to connect to MongoDB: {e}")
        except OperationFailure as e:
            raise MongitError(f"MongoDB operation failed: {e}")
        except Exception as e:
            raise MongitError(f"Unexpected error connecting to MongoDB: {e}")
    
    def close(self):
        """Close MongoDB connection"""
        if self.client:
            self.client.close()
    
    def compute_hash(self, content: bytes) -> str:
        """Compute SHA256 hash of content"""
        return hashlib.sha256(content).hexdigest()
    
    def is_binary_file(self, file_path: Path) -> bool:
        """
        Detect if a file is binary by checking for null bytes
        
        Args:
            file_path: Path to file
            
        Returns:
            True if binary, False if text
        """
        try:
            with open(file_path, 'rb') as f:
                chunk = f.read(8192)
                return b'\x00' in chunk
        except Exception:
            return True  # Assume binary if can't read
    
    def get_latest_version(self, file_path: str) -> Optional[Dict[str, Any]]:
        """
        Get the latest version of a file from MongoDB
        
        Args:
            file_path: Relative file path
            
        Returns:
            Document dict or None if file doesn't exist
        """
        doc = self.collection.find_one(
            {'project': self.project, 'file_path': file_path},
            sort=[('version', -1)]
        )
        return doc
    
    def push_file(self, local_path: Path, relative_path: str) -> bool:
        """
        Push a single file to MongoDB
        
        Args:
            local_path: Absolute path to local file
            relative_path: Relative path for storage
            
        Returns:
            True if file was pushed, False if skipped (unchanged)
        """
        try:
            # Read file content
            with open(local_path, 'rb') as f:
                content = f.read()
            
            # Compute hash
            content_hash = self.compute_hash(content)
            
            # Check if file exists with same hash
            latest = self.get_latest_version(relative_path)
            if latest and latest.get('hash') == content_hash:
                return False  # Skip unchanged file
            
            # Determine version number
            version = (latest['version'] + 1) if latest else 1
            
            # Encode content
            is_binary = self.is_binary_file(local_path)
            encoded_content = base64.b64encode(content).decode('utf-8')
            
            # Create document
            doc = {
                'project': self.project,
                'file_path': relative_path,
                'version': version,
                'hash': content_hash,
                'content': encoded_content,
                'is_binary': is_binary,
                'size': len(content),
                'updated_at': datetime.now(timezone.utc)
            }
            
            # Insert into MongoDB
            self.collection.insert_one(doc)
            return True
            
        except Exception as e:
            raise MongitError(f"Error pushing file {relative_path}: {e}")
    
    def push(self):
        """
        Push all files from the root directory to MongoDB
        """
        print(f"Pushing files from: {self.root_path}")
        print(f"Project: {self.project}")
        
        if len(self.gitignore.patterns) > 0:
            print(f"Loaded {len(self.gitignore.patterns)} .gitignore patterns")
        
        pushed_count = 0
        skipped_count = 0
        ignored_count = 0
        
        # Walk through directory
        for local_file in self.root_path.rglob('*'):
            if local_file.is_file():
                # Check if file should be ignored
                if self.gitignore.should_ignore(local_file):
                    ignored_count += 1
                    continue
                
                # Compute relative path (use forward slashes for cross-platform)
                relative_path = local_file.relative_to(self.root_path).as_posix()
                
                # Push file
                was_pushed = self.push_file(local_file, relative_path)
                if was_pushed:
                    pushed_count += 1
                    print(f"  ✓ {relative_path}")
                else:
                    skipped_count += 1
                    print(f"  ⊙ {relative_path} (unchanged)")
        
        print(f"\n✓ Push complete: {pushed_count} pushed, {skipped_count} skipped, {ignored_count} ignored")
    
    def pull_file(self, doc: Dict[str, Any], local_path: Path) -> bool:
        """
        Pull a single file from MongoDB to local filesystem
        
        Args:
            doc: MongoDB document
            local_path: Absolute path where to write the file
            
        Returns:
            True if file was written, False if skipped (unchanged)
        """
        try:
            # Check if local file exists with same hash
            if local_path.exists():
                with open(local_path, 'rb') as f:
                    local_content = f.read()
                local_hash = self.compute_hash(local_content)
                if local_hash == doc['hash']:
                    return False  # Skip unchanged file
            
            # Decode content
            content = base64.b64decode(doc['content'])
            
            # Create directory if needed
            local_path.parent.mkdir(parents=True, exist_ok=True)
            
            # Write file
            with open(local_path, 'wb') as f:
                f.write(content)
            
            return True
            
        except Exception as e:
            raise MongitError(f"Error pulling file {doc['file_path']}: {e}")
    
    def pull(self):
        """
        Pull all files from MongoDB to the root directory
        """
        print(f"Pulling files to: {self.root_path}")
        print(f"Project: {self.project}")
        
        # Create directory if it doesn't exist
        self.root_path.mkdir(parents=True, exist_ok=True)
        
        # Get all unique file paths for this project
        pipeline = [
            {'$match': {'project': self.project}},
            {'$sort': {'version': -1}},
            {'$group': {
                '_id': '$file_path',
                'doc': {'$first': '$$ROOT'}
            }}
        ]
        
        results = list(self.collection.aggregate(pipeline))
        
        if not results:
            print(f"No files found for project: {self.project}")
            return
        
        pulled_count = 0
        skipped_count = 0
        
        for result in results:
            doc = result['doc']
            file_path = doc['file_path']
            local_file = self.root_path / file_path
            
            was_pulled = self.pull_file(doc, local_file)
            if was_pulled:
                pulled_count += 1
                print(f"  ✓ {file_path} (v{doc['version']})")
            else:
                skipped_count += 1
                print(f"  ⊙ {file_path} (unchanged)")
        
        print(f"\n✓ Pull complete: {pulled_count} pulled, {skipped_count} skipped")
    
    def list_files(self):
        """List all files and their versions for the project"""
        print(f"Files for project: {self.project}")
        print("-" * 70)
        
        # Get all unique file paths with their latest version
        pipeline = [
            {'$match': {'project': self.project}},
            {'$sort': {'version': -1}},
            {'$group': {
                '_id': '$file_path',
                'version': {'$first': '$version'},
                'size': {'$first': '$size'},
                'updated_at': {'$first': '$updated_at'},
                'is_binary': {'$first': '$is_binary'}
            }},
            {'$sort': {'_id': 1}}
        ]
        
        results = list(self.collection.aggregate(pipeline))
        
        if not results:
            print(f"No files found for project: {self.project}")
            return
        
        # Print results
        for result in results:
            file_path = result['_id']
            version = result['version']
            size = result['size']
            updated_at = result['updated_at'].strftime('%Y-%m-%d %H:%M:%S')
            file_type = 'binary' if result.get('is_binary') else 'text'
            size_str = self._format_size(size)
            
            print(f"{file_path}")
            print(f"  Version: {version} | Size: {size_str} | Type: {file_type} | Updated: {updated_at}")
        
        print(f"\n✓ Total files: {len(results)}")
    
    def _format_size(self, size: int) -> str:
        """Format file size in human-readable format"""
        for unit in ['B', 'KB', 'MB', 'GB']:
            if size < 1024.0:
                return f"{size:.1f} {unit}"
            size /= 1024.0
        return f"{size:.1f} TB"
    
    def clean_cache_files(self):
        """
        Remove all Python cache files (.pyc, __pycache__, etc.) from MongoDB
        """
        print(f"Cleaning Python cache files from project: {self.project}")
        print("-" * 70)
        
        # Patterns to match cache files
        cache_patterns = [
            '.pyc', '.pyo', '.pyd', '.so',
            '__pycache__/', '.pytest_cache/', '.mypy_cache/',
            'dist/', 'build/', '.eggs/', '.egg-info/'
        ]
        
        deleted_count = 0
        
        # Find all files for this project
        all_files = self.collection.find({'project': self.project})
        
        for doc in all_files:
            file_path = doc['file_path']
            should_delete = False
            
            # Check if file matches any cache pattern
            for pattern in cache_patterns:
                if pattern in file_path or file_path.endswith(pattern.rstrip('/')):
                    should_delete = True
                    break
            
            if should_delete:
                # Delete all versions of this file
                result = self.collection.delete_many({
                    'project': self.project,
                    'file_path': file_path
                })
                deleted_count += result.deleted_count
                print(f"  ✓ Deleted: {file_path} ({result.deleted_count} versions)")
        
        print(f"\n✓ Cleanup complete: {deleted_count} documents deleted")
        
        if deleted_count == 0:
            print("  No cache files found in MongoDB")


def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(
        description='mongit - A minimal Git-like sync tool using MongoDB',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Push files
  python mongit.py push
  
  # Pull files
  python mongit.py pull
  
  # List files
  python mongit.py list
  
  # Clean cache files from MongoDB
  python mongit.py clean

Note: Place mongit.py in the parent folder of your project. It will:
  - Use the folder name as the project name
  - Sync all files recursively
  - Respect .gitignore patterns
  - Automatically ignore Python cache files (.pyc, __pycache__, etc.)
  - Use the MongoDB URI configured in the file
        """
    )
    
    parser.add_argument(
        'command',
        choices=['push', 'pull', 'list', 'clean'],
        help='Command to execute (clean removes .pyc and cache files from MongoDB)'
    )
    
    args = parser.parse_args()
    
    # Auto-detect root path (directory containing mongit.py)
    script_path = Path(__file__).resolve()
    root_path = script_path.parent
    
    # Auto-detect project name from folder name
    project_name = root_path.name
    
    # Use the configured MongoDB URI
    mongo_uri = MONGO_URI
    
    print(f"=== Mongit ===")
    print(f"Root path: {root_path}")
    print(f"Project: {project_name}")
    print()
    
    # Execute command
    mongit = Mongit(mongo_uri, project_name, root_path)
    
    try:
        mongit.connect()
        
        if args.command == 'push':
            mongit.push()
        elif args.command == 'pull':
            mongit.pull()
        elif args.command == 'list':
            mongit.list_files()
        elif args.command == 'clean':
            mongit.clean_cache_files()
        
    except MongitError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("\nOperation cancelled by user")
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        mongit.close()


if __name__ == '__main__':
    main()